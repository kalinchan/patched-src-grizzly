/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.spdy;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;

import org.glassfish.grizzly.CloseType;
import org.glassfish.grizzly.Closeable;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.GenericCloseListener;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.IOEventProcessingHandler;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.AttributeBuilder;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpContext;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.spdy.compression.SpdyDeflaterOutputStream;
import org.glassfish.grizzly.spdy.compression.SpdyInflaterOutputStream;
import org.glassfish.grizzly.spdy.frames.GoAwayFrame;
import org.glassfish.grizzly.spdy.frames.RstStreamFrame;
import org.glassfish.grizzly.spdy.frames.SpdyFrame;
import org.glassfish.grizzly.utils.DataStructures;
import org.glassfish.grizzly.utils.Holder;
import org.glassfish.grizzly.utils.NullaryFunction;

import static org.glassfish.grizzly.spdy.Constants.*;

/**
 *
 * @author oleksiys
 */
public final class SpdySession {
    private static final Attribute<SpdySession> SPDY_SESSION_ATTR =
            AttributeBuilder.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
            SpdySession.class.getName());
    
    private final boolean isServer;
    private final Connection<?> connection;
    
    private SpdyInflaterOutputStream inflaterOutputStream;
    private SpdyDeflaterOutputStream deflaterOutputStream;
    private DataOutputStream deflaterDataOutputStream;

    private final ReentrantLock deflaterLock = new ReentrantLock();
    private int deflaterCompressionLevel = Deflater.DEFAULT_COMPRESSION;
    
    private int lastPeerStreamId;
    private int lastLocalStreamId;

    private final ReentrantLock newClientStreamLock = new ReentrantLock();
    
    private volatile FilterChain upstreamChain;
    private volatile FilterChain downstreamChain;
    
    private Map<Integer, SpdyStream> streamsMap =
            DataStructures.<Integer, SpdyStream>getConcurrentMap();
    
    final List<SpdyStream> streamsToFlushInput = new ArrayList<SpdyStream>();
    
    private final Object sessionLock = new Object();
    
    private CloseType closeFlag;
    
    private int peerInitialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    private volatile int localInitialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    
    private volatile int localMaxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private int peerMaxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;

    private final StreamBuilder streamBuilder = new StreamBuilder();
    
    public static SpdySession get(final Connection connection) {
        return SPDY_SESSION_ATTR.get(connection);
    }
    
    public static void bind(final Connection connection, final SpdySession spdySession) {
        SPDY_SESSION_ATTR.set(connection, spdySession);
    }
    
    private final Holder<?> addressHolder;

    final SpdyHandlerFilter handlerFilter;

    
    public SpdySession(final Connection<?> connection,
                       final boolean isServer,
                       final SpdyHandlerFilter handlerFilter) {
        this.connection = connection;
        this.isServer = isServer;
        this.handlerFilter = handlerFilter;
        
        if (isServer) {
            lastLocalStreamId = 0;
            lastPeerStreamId = -1;
        } else {
            lastLocalStreamId = -1;
            lastPeerStreamId = 0;
        }
        
        addressHolder = Holder.<Object>lazyHolder(new NullaryFunction<Object>() {
            @Override
            public Object evaluate() {
                return connection.getPeerAddress();
            }
        });
        
        connection.addCloseListener(new ConnectionCloseListener());
    }

    public StreamBuilder getStreamBuilder() {
        return streamBuilder;
    }
    
    public int getPeerInitialWindowSize() {
        return peerInitialWindowSize;
    }

    void setPeerInitialWindowSize(int peerInitialWindowSize) {
        this.peerInitialWindowSize = peerInitialWindowSize;
    }

    public int getLocalInitialWindowSize() {
        return localInitialWindowSize;
    }

    public void setLocalInitialWindowSize(int localInitialWindowSize) {
        this.localInitialWindowSize = localInitialWindowSize;
    }

    /**
     * Returns the maximum number of concurrent streams allowed for this session by our side.
     */
    public int getLocalMaxConcurrentStreams() {
        return localMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by our side.
     */
    public void setLocalMaxConcurrentStreams(int localMaxConcurrentStreams) {
        this.localMaxConcurrentStreams = localMaxConcurrentStreams;
    }

    /**
     * Returns the maximum number of concurrent streams allowed for this session by peer.
     */
    public int getPeerMaxConcurrentStreams() {
        return peerMaxConcurrentStreams;
    }

    /**
     * Sets the default maximum number of concurrent streams allowed for this session by peer.
     */
    void setPeerMaxConcurrentStreams(int peerMaxConcurrentStreams) {
        this.peerMaxConcurrentStreams = peerMaxConcurrentStreams;
    }

    
    int getNextLocalStreamId() {
        lastLocalStreamId += 2;
        return lastLocalStreamId;
    }
    
    public Connection getConnection() {
        return connection;
    }

    public MemoryManager getMemoryManager() {
        return connection.getTransport().getMemoryManager();
    }
    
    public boolean isServer() {
        return isServer;
    }
    
    public SpdyStream getStream(final int streamId) {
        return streamsMap.get(streamId);
    }
    
    /**
     * If the session is still open - closes it and sends GOAWAY frame to a peer,
     * otherwise if the session was already closed - does nothing.
     * 
     * @param statusCode GOAWAY status code.
     */
    public void goAway(final int statusCode) {
        final SpdyFrame goAwayFrame = setGoAwayLocally(statusCode);
        if (goAwayFrame != null) {
            writeDownStream(goAwayFrame);
        }
    }

    GoAwayFrame setGoAwayLocally(final int statusCode) {
        final int lastPeerStreamIdLocal = close();
        if (lastPeerStreamIdLocal == -1) {
            return null; // SpdySession is already in go-away state
        }
        
        return GoAwayFrame.builder()
                .lastGoodStreamId(lastPeerStreamIdLocal)
                .statusCode(statusCode)
                .build();
    }
    
    SpdyInflaterOutputStream getInflaterOutputStream() {
        if (inflaterOutputStream == null) {
            inflaterOutputStream = new SpdyInflaterOutputStream(
                    getMemoryManager());
        }
        
        return inflaterOutputStream;
    }

    public int getDeflaterCompressionLevel() {
        return deflaterCompressionLevel;
    }

    public void setDeflaterCompressionLevel(int deflaterCompressionLevel) {
        if (deflaterOutputStream != null) {
            throw new IllegalStateException("Deflater has been initialized already");
        }
        
        this.deflaterCompressionLevel = deflaterCompressionLevel;
    }    

    ReentrantLock getDeflaterLock() {
        return deflaterLock;
    }

    SpdyDeflaterOutputStream getDeflaterOutputStream() {
        if (deflaterOutputStream == null) {
            deflaterOutputStream = new SpdyDeflaterOutputStream(
                    getMemoryManager(),
                    deflaterCompressionLevel);
        }
        
        return deflaterOutputStream;
    }
    
    DataOutputStream getDeflaterDataOutputStream() {
        if (deflaterDataOutputStream == null) {
            deflaterDataOutputStream = new DataOutputStream(
                    getDeflaterOutputStream());
        }
        
        return deflaterDataOutputStream;
    }

    ReentrantLock getNewClientStreamLock() {
        return newClientStreamLock;
    }

    SpdyStream acceptStream(final HttpRequestPacket spdyRequest,
            final int streamId, final int associatedToStreamId, 
            final int priority, final int slot, final boolean isUnidirectional)
            throws SpdySessionException {
        
        final SpdyStream spdyStream = SpdyStream.create(this, spdyRequest,
                streamId, associatedToStreamId,
                priority, slot, isUnidirectional);
        
        synchronized(sessionLock) {
            if (isClosed()) {
                return null; // if the session is closed is set - return null to ignore stream creation
            }
            
            if (streamsMap.size() >= getLocalMaxConcurrentStreams()) {
                // throw Session level exception because headers were not decompressed,
                // so compression context is lost
                throw new SpdySessionException(streamId,
                        GoAwayFrame.INTERNAL_ERROR_STATUS,
                        RstStreamFrame.REFUSED_STREAM);
            }
            
            streamsMap.put(streamId, spdyStream);
            lastPeerStreamId = streamId;
        }
        
        return spdyStream;
    }

    /**
     * Method is not thread-safe, it is expected that it will be called
     * within {@link #getNewClientStreamLock()} lock scope.
     * The caller code is responsible for obtaining and releasing the mentioned
     * {@link #getNewClientStreamLock()} lock.
     */
    SpdyStream openStream(final HttpRequestPacket spdyRequest,
            final int streamId, final int associatedToStreamId, 
            final int priority, final int slot, final boolean isUnidirectional,
            final boolean fin)
            throws SpdyStreamException {
        
        spdyRequest.setExpectContent(!fin);
        final SpdyStream spdyStream = SpdyStream.create(this, spdyRequest,
                streamId, associatedToStreamId,
                priority, slot, isUnidirectional);
        
        synchronized(sessionLock) {
            if (isClosed()) {
                return null; // if the session is closed is set - return null to ignore stream creation
            }
            
            if (streamsMap.size() >= getLocalMaxConcurrentStreams()) {
                throw new SpdyStreamException(streamId, RstStreamFrame.REFUSED_STREAM);
            }
            
            if (associatedToStreamId > 0) {
                final SpdyStream mainStream = getStream(associatedToStreamId);
                if (mainStream == null) {
                    throw new SpdyStreamException(streamId, RstStreamFrame.REFUSED_STREAM,
                            "The parent stream does not exist");
                }
                
                mainStream.addAssociatedStream(spdyStream);
            }
            
            streamsMap.put(streamId, spdyStream);
            lastLocalStreamId = streamId;
        }
        
        return spdyStream;
    }

   
    void writeDownStream(final SpdyFrame frame) {
        writeDownStream(frame, null);
    }
    
    void writeDownStream(final SpdyFrame frame,
            final CompletionHandler<WriteResult> completionHandler) {
        
        downstreamChain.write(connection,
                null, frame, completionHandler, (MessageCloner) null);        
    }

    <K> void writeDownStream(final K anyMessage,
            final CompletionHandler<WriteResult> completionHandler,
            final MessageCloner<K> messageCloner) {
        
        downstreamChain.write(connection,
                null, anyMessage, completionHandler, messageCloner);        
    }

    boolean initCommunication(final FilterChainContext context,
            final boolean isUpStream) {
        
        if (downstreamChain == null) {
            synchronized(this) {
                if (downstreamChain == null) {
                    if (isUpStream) {
                        upstreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getFilterIdx(), context.getEndIdx());

                        downstreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getStartIdx(), context.getFilterIdx());
                    } else {
                        upstreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getFilterIdx(), context.getFilterChain().size());

                        downstreamChain = (FilterChain) context.getFilterChain().subList(
                                context.getEndIdx() + 1, context.getFilterIdx());
                    }
                    
                    return true;
                }
            }
        }
        
        return false;
    }
    
    FilterChain getUpstreamChain() {
        return upstreamChain;
    }
    
    FilterChain getDownstreamChain() {
        return downstreamChain;
    }
    
    /**
     * Method is called, when the session closing is initiated locally.
     */
    private int close() {
        synchronized (sessionLock) {
            if (isClosed()) {
                return -1;
            }
            
            closeFlag = CloseType.LOCALLY;
            return lastPeerStreamId > 0 ? lastPeerStreamId : 0;
        }
    }
    
    /**
     * Method is called, when GOAWAY is initiated by peer
     */
    void setGoAwayByPeer(final int lastGoodStreamId) {
        synchronized (sessionLock) {
            // @TODO Notify pending SYNC_STREAMS if streams were aborted
            closeFlag = CloseType.REMOTELY;
        }
    }
    
    Object getSessionLock() {
        return sessionLock;
    }

    /**
     * Called from {@link SpdyStream} once stream is completely closed.
     */
    void deregisterStream(final SpdyStream spdyStream) {
        streamsMap.remove(spdyStream.getStreamId());
        
        final boolean isCloseSession;
        synchronized (sessionLock) {
            // If we're in GOAWAY state and there are no streams left - close this session
            isCloseSession = isClosed() && streamsMap.isEmpty();
        }
        
        if (isCloseSession) {
            closeSession();
        }
    }

    /**
     * Close the session
     */
    private void closeSession() {
        connection.closeSilently();
    }

    private boolean isClosed() {
        return closeFlag != null;
    }

    void sendMessageUpstreamWithParseNotify(final SpdyStream spdyStream,
                                            final HttpContent httpContent) {
        final FilterChainContext upstreamContext =
                        upstreamChain.obtainFilterChainContext(connection);
        handlerFilter.onHttpContentParsed(httpContent, upstreamContext);
        final HttpHeader header = httpContent.getHttpHeader();
        if (httpContent.isLast()) {
            handlerFilter.onHttpPacketParsed(header, upstreamContext);
        }

        if (header.isSkipRemainder()) {
            return;
        }

        sendMessageUpstream(spdyStream, httpContent, upstreamContext);
    }

    void sendMessageUpstream(final SpdyStream spdyStream,
                             final HttpPacket message) {
        final FilterChainContext upstreamContext =
                upstreamChain.obtainFilterChainContext(connection);
        sendMessageUpstream(spdyStream, message, upstreamContext);
    }

    private void sendMessageUpstream(final SpdyStream spdyStream,
                                     final HttpPacket message,
                                     final FilterChainContext upstreamContext) {

        upstreamContext.getInternalContext().setIoEvent(IOEvent.READ,
                new IOEventProcessingHandler.Adapter() {
                    @Override
                    public void onReregister(final Context context) throws IOException {
                        spdyStream.inputBuffer.onReadEventComplete();
                    }

                    @Override
                    public void onComplete(Context context, Object data) throws IOException {
                        spdyStream.inputBuffer.onReadEventComplete();
                    }
                });

        upstreamContext.setMessage(message);
        upstreamContext.setAddressHolder(addressHolder);

        HttpContext.newInstance(upstreamContext, spdyStream, spdyStream, spdyStream);
        ProcessorExecutor.execute(upstreamContext.getInternalContext());
    }

    public final class StreamBuilder {

        private StreamBuilder() {
        }
        
        public BidirectionalBuilder bidirectional() {
            return new BidirectionalBuilder();
        }
        
        public UnidirectionalBuilder unidirectional() {
            return new UnidirectionalBuilder();
        }
    }
    
    public final class UnidirectionalBuilder extends HttpHeader.Builder<UnidirectionalBuilder> {
        private final SpdyRequest request;
        
        private int associatedToStreamId;
        private int priority;
        private int slot;
        private boolean isFin;
        
        protected UnidirectionalBuilder() {
            request = SpdyRequest.create();
            packet = request.getResponse();
            
            packet.setSecure(true);
        }

        /**
         * Set the request URI.
         *
         * @param uri the request URI.
         */
        public UnidirectionalBuilder uri(final String uri) {
            request.setRequestURI(uri);
            return this;
        }

        /**
         * Set the <code>query</code> portion of the request URI.
         *
         * @param query the query String
         *
         * @return the current <code>Builder</code>
         */
        public UnidirectionalBuilder query(final String query) {
            request.setQueryString(query);
            return this;
        }

        /**
         * Set the <code>associatedToStreamId</code> parameter of a {@link SpdyStream}.
         *
         * @param associatedToStreamId the associatedToStreamId
         *
         * @return the current <code>Builder</code>
         */
        public UnidirectionalBuilder associatedToStreamId(final int associatedToStreamId) {
            this.associatedToStreamId = associatedToStreamId;
            return this;
        }

        /**
         * Set the <code>priority</code> parameter of a {@link SpdyStream}.
         *
         * @param priority the priority
         *
         * @return the current <code>Builder</code>
         */
        public UnidirectionalBuilder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Set the <code>slot</code> parameter of a {@link SpdyStream}.
         *
         * @param slot the slot
         *
         * @return the current <code>Builder</code>
         */
        public UnidirectionalBuilder slot(final int slot) {
            this.slot = slot;
            return this;
        }
        
        /**
         * Sets the <code>fin</code> flag of a {@link SpdyStream}.
         * 
         * @param fin
         * 
         * @return the current <code>Builder</code>
         */
        public UnidirectionalBuilder fin(final boolean fin) {
            this.isFin = fin;
            return this;
        }
        
        /**
         * Build the <tt>HttpRequestPacket</tt> message.
         *
         * @return <tt>HttpRequestPacket</tt>
         */
        @SuppressWarnings("unchecked")
        public final SpdyStream open() throws SpdyStreamException {
            newClientStreamLock.lock();

            try {
                final SpdyStream spdyStream = openStream(
                        request,
                        getNextLocalStreamId(),
                        associatedToStreamId, priority,
                        slot, true, isFin);
                
                
                connection.write(packet);
                
                return spdyStream;
            } finally {
                newClientStreamLock.unlock();
            }
        }
    }    
    
    public final class BidirectionalBuilder extends HttpHeader.Builder<BidirectionalBuilder> {
        private int priority;
        private int slot;
        private boolean isFin;
        
        protected BidirectionalBuilder() {
            packet = SpdyRequest.create();
            packet.setSecure(true);
        }

        /**
         * Set the HTTP request method.
         * @param method the HTTP request method..
         */
        public BidirectionalBuilder method(final Method method) {
            ((HttpRequestPacket) packet).setMethod(method);
            return this;
        }

        /**
         * Set the HTTP request method.
         * @param method the HTTP request method. Format is "GET|POST...".
         */
        public BidirectionalBuilder method(final String method) {
            ((HttpRequestPacket) packet).setMethod(method);
            return this;
        }

        /**
         * Set the request URI.
         *
         * @param uri the request URI.
         */
        public BidirectionalBuilder uri(final String uri) {
            ((HttpRequestPacket) packet).setRequestURI(uri);
            return this;
        }

        /**
         * Set the <code>query</code> portion of the request URI.
         *
         * @param query the query String
         *
         * @return the current <code>Builder</code>
         */
        public BidirectionalBuilder query(final String query) {
            ((HttpRequestPacket) packet).setQueryString(query);
            return this;
        }

        /**
         * Set the <code>priority</code> parameter of a {@link SpdyStream}.
         *
         * @param priority the priority
         *
         * @return the current <code>Builder</code>
         */
        public BidirectionalBuilder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        /**
         * Set the <code>slot</code> parameter of a {@link SpdyStream}.
         *
         * @param slot the slot
         *
         * @return the current <code>Builder</code>
         */
        public BidirectionalBuilder slot(final int slot) {
            this.slot = slot;
            return this;
        }
        
        /**
         * Sets the <code>fin</code> flag of a {@link SpdyStream}.
         * 
         * @param fin
         * 
         * @return the current <code>Builder</code>
         */
        public BidirectionalBuilder fin(final boolean fin) {
            this.isFin = fin;
            return this;
        }
        
        /**
         * Build the <tt>HttpRequestPacket</tt> message.
         *
         * @return <tt>HttpRequestPacket</tt>
         */
        @SuppressWarnings("unchecked")
        public final SpdyStream open() throws SpdyStreamException {
            newClientStreamLock.lock();

            try {
                final SpdyStream spdyStream = openStream(
                        (HttpRequestPacket) packet,
                        getNextLocalStreamId(),
                        0, priority, slot, false, isFin);
                
                
                connection.write(packet);
                
                return spdyStream;
            } finally {
                newClientStreamLock.unlock();
            }
        }
    }
    
    private final class ConnectionCloseListener implements GenericCloseListener {

        @Override
        public void onClosed(final Closeable closeable, final CloseType type)
                throws IOException {
            
            final boolean isClosing;
            synchronized (sessionLock) {
                isClosing = !isClosed();
                if (isClosing) {
                    closeFlag = type;
                }
            }
            
            if (isClosing) {
                for (SpdyStream stream : streamsMap.values()) {
                    stream.closedRemotely();
                }
            }
        }
    }
}