/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
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
 *
 */

package com.sun.grizzly;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.LinkedList;

/**
 * Abstract class simplifies the implementation of
 * {@link SocketConnectorHandler}
 * interface by preimplementing some of its methods.
 * 
 * @author Alexey Stashok
 */
public abstract class AbstractSocketConnectorHandler
        implements SocketConnectorHandler {
    
    protected Transport transport;

    private Processor processor;
    private ProcessorSelector processorSelector;

    protected final Collection<ConnectionMonitoringProbe> probes =
            new LinkedList<ConnectionMonitoringProbe>();

    public AbstractSocketConnectorHandler(Transport transport) {
        this.transport = transport;
        this.processor = transport.getProcessor();
        this.processorSelector = transport.getProcessorSelector();
    }
    
    @Override
    public GrizzlyFuture<Connection> connect(String host, int port)
            throws IOException {
        return connect(new InetSocketAddress(host, port));
    }
    
    @Override
    public GrizzlyFuture<Connection> connect(SocketAddress remoteAddress)
            throws IOException {
        return connect(remoteAddress, (SocketAddress) null);
    }

    @Override
    public GrizzlyFuture<Connection> connect(SocketAddress remoteAddress,
            CompletionHandler<Connection> completionHandler)
            throws IOException {
        return connect(remoteAddress, null, completionHandler);
    }

    @Override
    public GrizzlyFuture<Connection> connect(SocketAddress remoteAddress,
            SocketAddress localAddress) throws IOException {
        return connect(remoteAddress, localAddress, null);
    }

    @Override
    public abstract GrizzlyFuture<Connection> connect(SocketAddress remoteAddress,
            SocketAddress localAddress,
            CompletionHandler<Connection> completionHandler) throws IOException;


    @Override
    public Processor getProcessor() {
        return processor;
    }

    @Override
    public void setProcessor(Processor processor) {
        this.processor = processor;
    }

    @Override
    public ProcessorSelector getProcessorSelector() {
        return processorSelector;
    }

    @Override
    public void setProcessorSelector(ProcessorSelector processorSelector) {
        this.processorSelector = processorSelector;
    }

    @Override
    public void addMonitoringProbe(ConnectionMonitoringProbe probe) {
        probes.add(probe);
    }

    @Override
    public boolean removeMonitoringProbe(ConnectionMonitoringProbe probe) {
        return probes.remove(probe);
    }

    @Override
    public ConnectionMonitoringProbe[] getMonitoringProbes() {
        return probes.toArray(new ConnectionMonitoringProbe[0]);
    }

    /**
     * Preconfigures {@link Connection} object before actual connecting phase
     * will be started.
     * 
     * @param connection {@link Connection} to preconfigure.
     */
    protected void preConfigure(Connection connection) {
    }
}
