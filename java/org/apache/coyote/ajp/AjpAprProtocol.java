/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.ajp;

import org.apache.coyote.*;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.AprEndpoint;
import org.apache.tomcat.util.net.AprEndpoint.Handler;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.res.StringManager;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class AjpAprProtocol extends AbstractProtocol
        implements MBeanRegistration
{


    protected static org.apache.juli.logging.Log log =
            org.apache.juli.logging.LogFactory.getLog(AjpAprProtocol.class);

    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);


    // ------------------------------------------------------------ Constructor
    protected ObjectName tpOname;


    // ----------------------------------------------------- Instance Variables
    protected ObjectName rgOname;
    /**
     * Associated APR endpoint.
     */
    protected AprEndpoint endpoint = new AprEndpoint();
    /**
     * Configuration attributes.
     */
    protected Hashtable attributes = new Hashtable();
    /**
     * Processor cache.
     */
    protected int processorCache = -1;
    /**
     * Should authentication be done in the native webserver layer,
     * or in the Servlet container ?
     */
    protected boolean tomcatAuthentication = true;
    /**
     * Required secret.
     */
    protected String requiredSecret = null;
    /**
     * AJP packet size.
     */
    protected int packetSize = Constants.MAX_PACKET_SIZE;


    // --------------------------------------------------------- Public Methods
    /**
     * When client certificate information is presented in a form other than
     * instances of {@link java.security.cert.X509Certificate} it needs to be
     * converted before it can be used and this property controls which JSSE
     * provider is used to perform the conversion. For example it is used with
     * the AJP connectors, the HTTP APR connector and with the
     * {@link org.apache.catalina.valves.SSLValve}. If not specified, the
     * default provider will be used.
     */
    protected String clientCertProvider = null;
    protected String domain;
    protected ObjectName oname;
    protected MBeanServer mserver;
    /**
     * Adapter which will process the requests recieved by this endpoint.
     */
    private Adapter adapter;
    /**
     * Connection handler for AJP.
     */
    private AjpConnectionHandler cHandler;


    public AjpAprProtocol()
    {
        cHandler = new AjpConnectionHandler(this);
        setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
        setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
        //setServerSoTimeout(Constants.DEFAULT_SERVER_SOCKET_TIMEOUT);
        setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
    }

    protected final AbstractEndpoint getEndpoint()
    {
        return endpoint;
    }

    /**
     * Pass config info
     */
    public void setAttribute(String name, Object value)
    {
        if (log.isTraceEnabled())
        {
            log.trace(sm.getString("ajpprotocol.setattribute", name, value));
        }
        attributes.put(name, value);
    }

    public Object getAttribute(String key)
    {
        if (log.isTraceEnabled())
        {
            log.trace(sm.getString("ajpprotocol.getattribute", key));
        }
        return attributes.get(key);
    }

    public Iterator getAttributeNames()
    {
        return attributes.keySet().iterator();
    }

    public Adapter getAdapter()
    {
        return adapter;
    }

    /**
     * The adapter, used to call the connector
     */
    public void setAdapter(Adapter adapter)
    {
        this.adapter = adapter;
    }

    /**
     * Start the protocol
     */
    public void init() throws Exception
    {
        endpoint.setName(getName());
        endpoint.setHandler(cHandler);
        endpoint.setUseSendfile(false);

        try
        {
            endpoint.init();
        }
        catch (Exception ex)
        {
            log.error(sm.getString("ajpprotocol.endpoint.initerror"), ex);
            throw ex;
        }
        if (log.isInfoEnabled())
        {
            log.info(sm.getString("ajpprotocol.init", getName()));
        }
    }

    public void start() throws Exception
    {
        if (this.domain != null)
        {
            try
            {
                tpOname = new ObjectName
                        (domain + ":" + "type=ThreadPool,name=" + getName());
                Registry.getRegistry(null, null)
                        .registerComponent(endpoint, tpOname, null);
            }
            catch (Exception e)
            {
                log.error("Can't register threadpool");
            }
            rgOname = new ObjectName
                    (domain + ":type=GlobalRequestProcessor,name=" + getName());
            Registry.getRegistry(null, null).registerComponent
                    (cHandler.global, rgOname, null);
        }

        try
        {
            endpoint.start();
        }
        catch (Exception ex)
        {
            log.error(sm.getString("ajpprotocol.endpoint.starterror"), ex);
            throw ex;
        }
        if (log.isInfoEnabled())
            log.info(sm.getString("ajpprotocol.start", getName()));
    }

    public void pause() throws Exception
    {
        try
        {
            endpoint.pause();
        }
        catch (Exception ex)
        {
            log.error(sm.getString("ajpprotocol.endpoint.pauseerror"), ex);
            throw ex;
        }
        if (log.isInfoEnabled())
            log.info(sm.getString("ajpprotocol.pause", getName()));
    }

    public void resume() throws Exception
    {
        try
        {
            endpoint.resume();
        }
        catch (Exception ex)
        {
            log.error(sm.getString("ajpprotocol.endpoint.resumeerror"), ex);
            throw ex;
        }
        if (log.isInfoEnabled())
            log.info(sm.getString("ajpprotocol.resume", getName()));
    }

    public void destroy() throws Exception
    {
        if (log.isInfoEnabled())
            log.info(sm.getString("ajpprotocol.stop", getName()));
        endpoint.destroy();
        if (tpOname != null)
            Registry.getRegistry(null, null).unregisterComponent(tpOname);
        if (rgOname != null)
            Registry.getRegistry(null, null).unregisterComponent(rgOname);
    }

    // *
    public String getName()
    {
        String encodedAddr = "";
        if (getAddress() != null)
        {
            encodedAddr = "" + getAddress();
            if (encodedAddr.startsWith("/"))
                encodedAddr = encodedAddr.substring(1);
            encodedAddr = URLEncoder.encode(encodedAddr) + "-";
        }
        return ("ajp-" + encodedAddr + endpoint.getPort());
    }

    public int getProcessorCache()
    {
        return this.processorCache;
    }

    public void setProcessorCache(int processorCache)
    {
        this.processorCache = processorCache;
    }

    public Executor getExecutor()
    {
        return endpoint.getExecutor();
    }

    public void setExecutor(Executor executor)
    {
        endpoint.setExecutor(executor);
    }

    public int getMaxThreads()
    {
        return endpoint.getMaxThreads();
    }

    public void setMaxThreads(int maxThreads)
    {
        endpoint.setMaxThreads(maxThreads);
    }

    public int getThreadPriority()
    {
        return endpoint.getThreadPriority();
    }

    public void setThreadPriority(int threadPriority)
    {
        endpoint.setThreadPriority(threadPriority);
    }

    public int getBacklog()
    {
        return endpoint.getBacklog();
    }

    public void setBacklog(int backlog)
    {
        endpoint.setBacklog(backlog);
    }

    public int getPort()
    {
        return endpoint.getPort();
    }

    public void setPort(int port)
    {
        endpoint.setPort(port);
    }

    public InetAddress getAddress()
    {
        return endpoint.getAddress();
    }

    public void setAddress(InetAddress ia)
    {
        endpoint.setAddress(ia);
    }

    public boolean getTcpNoDelay()
    {
        return endpoint.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay)
    {
        endpoint.setTcpNoDelay(tcpNoDelay);
    }

    public int getSoLinger()
    {
        return endpoint.getSoLinger();
    }

    public void setSoLinger(int soLinger)
    {
        endpoint.setSoLinger(soLinger);
    }

    public int getSoTimeout()
    {
        return endpoint.getSoTimeout();
    }

    public void setSoTimeout(int soTimeout)
    {
        endpoint.setSoTimeout(soTimeout);
    }

    public boolean getTomcatAuthentication()
    {
        return tomcatAuthentication;
    }

    public void setTomcatAuthentication(boolean tomcatAuthentication)
    {
        this.tomcatAuthentication = tomcatAuthentication;
    }

    public void setRequiredSecret(String requiredSecret)
    {
        this.requiredSecret = requiredSecret;
    }

    public int getPacketSize()
    {
        return packetSize;
    }

    public void setPacketSize(int packetSize)
    {
        if (packetSize < Constants.MAX_PACKET_SIZE)
        {
            this.packetSize = Constants.MAX_PACKET_SIZE;
        } else
        {
            this.packetSize = packetSize;
        }
    }

    /**
     * The number of seconds Tomcat will wait for a subsequent request
     * before closing the connection.
     */
    public int getKeepAliveTimeout()
    {
        return endpoint.getKeepAliveTimeout();
    }

    public void setKeepAliveTimeout(int timeout)
    {
        endpoint.setKeepAliveTimeout(timeout);
    }

    public boolean getUseSendfile()
    {
        return endpoint.getUseSendfile();
    }

    public void setUseSendfile(boolean useSendfile)
    { /* No sendfile for AJP */ }

    public int getPollTime()
    {
        return endpoint.getPollTime();
    }

    public void setPollTime(int pollTime)
    {
        endpoint.setPollTime(pollTime);
    }

    public int getPollerSize()
    {
        return endpoint.getPollerSize();
    }


    // --------------------------------------  AjpConnectionHandler Inner Class

    public void setPollerSize(int pollerSize)
    {
        endpoint.setPollerSize(pollerSize);
    }


    // -------------------- Various implementation classes --------------------

    public String getClientCertProvider()
    {
        return clientCertProvider;
    }

    public void setClientCertProvider(String s)
    {
        this.clientCertProvider = s;
    }

    public ObjectName getObjectName()
    {
        return oname;
    }

    public String getDomain()
    {
        return domain;
    }

    public ObjectName preRegister(MBeanServer server,
                                  ObjectName name) throws Exception
    {
        oname = name;
        mserver = server;
        domain = name.getDomain();
        return name;
    }

    public void postRegister(Boolean registrationDone)
    {
    }

    public void preDeregister() throws Exception
    {
    }

    public void postDeregister()
    {
    }

    protected static class AjpConnectionHandler implements Handler
    {

        protected AjpAprProtocol proto;
        protected AtomicLong registerCount = new AtomicLong(0);
        protected RequestGroupInfo global = new RequestGroupInfo();

        protected ConcurrentLinkedQueue<AjpAprProcessor> recycledProcessors =
                new ConcurrentLinkedQueue<AjpAprProcessor>()
                {
                    protected AtomicInteger size = new AtomicInteger(0);

                    public boolean offer(AjpAprProcessor processor)
                    {
                        boolean offer = (proto.processorCache == -1) ? true : (size.get() < proto.processorCache);
                        //avoid over growing our cache or add after we have stopped
                        boolean result = false;
                        if (offer)
                        {
                            result = super.offer(processor);
                            if (result)
                            {
                                size.incrementAndGet();
                            }
                        }
                        if (!result) unregister(processor);
                        return result;
                    }

                    public AjpAprProcessor poll()
                    {
                        AjpAprProcessor result = super.poll();
                        if (result != null)
                        {
                            size.decrementAndGet();
                        }
                        return result;
                    }

                    public void clear()
                    {
                        AjpAprProcessor next = poll();
                        while (next != null)
                        {
                            unregister(next);
                            next = poll();
                        }
                        super.clear();
                        size.set(0);
                    }
                };

        public AjpConnectionHandler(AjpAprProtocol proto)
        {
            this.proto = proto;
        }

        // FIXME: Support for this could be added in AJP as well
        public SocketState event(long socket, SocketStatus status)
        {
            return SocketState.CLOSED;
        }

        public SocketState process(long socket)
        {
            AjpAprProcessor processor = recycledProcessors.poll();
            try
            {

                if (processor == null)
                {
                    processor = createProcessor();
                }

                if (processor instanceof ActionHook)
                {
                    ((ActionHook) processor).action(ActionCode.ACTION_START, null);
                }

                if (processor.process(socket))
                {
                    return SocketState.OPEN;
                } else
                {
                    return SocketState.CLOSED;
                }

            }
            catch (java.net.SocketException e)
            {
                // SocketExceptions are normal
                AjpAprProtocol.log.debug
                        (sm.getString
                                ("ajpprotocol.proto.socketexception.debug"), e);
            }
            catch (java.io.IOException e)
            {
                // IOExceptions are normal
                AjpAprProtocol.log.debug
                        (sm.getString
                                ("ajpprotocol.proto.ioexception.debug"), e);
            }
            // Future developers: if you discover any other
            // rare-but-nonfatal exceptions, catch them here, and log as
            // above.
            catch (Throwable e)
            {
                // any other exception or error is odd. Here we log it
                // with "ERROR" level, so it will show up even on
                // less-than-verbose logs.
                AjpAprProtocol.log.error
                        (sm.getString("ajpprotocol.proto.error"), e);
            }
            finally
            {
                if (processor instanceof ActionHook)
                {
                    ((ActionHook) processor).action(ActionCode.ACTION_STOP, null);
                }
                recycledProcessors.offer(processor);
            }
            return SocketState.CLOSED;
        }

        protected AjpAprProcessor createProcessor()
        {
            AjpAprProcessor processor = new AjpAprProcessor(proto.packetSize, proto.endpoint);
            processor.setAdapter(proto.adapter);
            processor.setTomcatAuthentication(proto.tomcatAuthentication);
            processor.setRequiredSecret(proto.requiredSecret);
            processor.setClientCertProvider(proto.getClientCertProvider());
            register(processor);
            return processor;
        }

        protected void register(AjpAprProcessor processor)
        {
            if (proto.getDomain() != null)
            {
                synchronized (this)
                {
                    try
                    {
                        long count = registerCount.incrementAndGet();
                        RequestInfo rp = processor.getRequest().getRequestProcessor();
                        rp.setGlobalProcessor(global);
                        ObjectName rpName = new ObjectName
                                (proto.getDomain() + ":type=RequestProcessor,worker="
                                        + proto.getName() + ",name=AjpRequest" + count);
                        if (log.isDebugEnabled())
                        {
                            log.debug("Register " + rpName);
                        }
                        Registry.getRegistry(null, null).registerComponent(rp, rpName, null);
                        rp.setRpName(rpName);
                    }
                    catch (Exception e)
                    {
                        log.warn("Error registering request");
                    }
                }
            }
        }

        protected void unregister(AjpAprProcessor processor)
        {
            if (proto.getDomain() != null)
            {
                synchronized (this)
                {
                    try
                    {
                        RequestInfo rp = processor.getRequest().getRequestProcessor();
                        rp.setGlobalProcessor(null);
                        ObjectName rpName = rp.getRpName();
                        if (log.isDebugEnabled())
                        {
                            log.debug("Unregister " + rpName);
                        }
                        Registry.getRegistry(null, null).unregisterComponent(rpName);
                        rp.setRpName(null);
                    }
                    catch (Exception e)
                    {
                        log.warn("Error unregistering request", e);
                    }
                }
            }
        }

    }


}
