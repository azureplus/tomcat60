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

package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;
import org.apache.tomcat.util.net.jsse.JSSESocketFactory;
import org.apache.tomcat.util.net.jsse.NioX509KeyManager;
import org.apache.tomcat.util.res.StringManager;

import javax.net.ssl.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.security.KeyStore;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 * <p/>
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 * @author Filip Hanik
 */
public class NioEndpoint extends AbstractEndpoint
{


    // -------------------------------------------------------------- Constants


    /**
     * The Request attribute key for the cipher suite.
     */
    public static final String CIPHER_SUITE_KEY = "javax.servlet.request.cipher_suite";
    /**
     * The Request attribute key for the key size.
     */
    public static final String KEY_SIZE_KEY = "javax.servlet.request.key_size";
    /**
     * The Request attribute key for the client certificate chain.
     */
    public static final String CERTIFICATE_KEY = "javax.servlet.request.X509Certificate";
    /**
     * The Request attribute key for the session id.
     * This one is a Tomcat extension to the Servlet spec.
     */
    public static final String SESSION_ID_KEY = "javax.servlet.request.ssl_session";
    public static final int OP_REGISTER = 0x100; //register interest op
    public static final int OP_CALLBACK = 0x200; //callback interest op
    /**
     * Make sure this string has already been allocated
     */
    protected static final String oomParachuteMsg =
            "SEVERE:Memory usage is low, parachute is non existent, your system may start failing.";
    protected static Log log = LogFactory.getLog(NioEndpoint.class);

    // ----------------------------------------------------------------- Fields
    protected static StringManager sm =
            StringManager.getManager("org.apache.tomcat.util.net.res");
    /**
     * Available workers.
     */
    protected WorkerStack workers = null;
    /**
     * Running state of the endpoint.
     */
    protected volatile boolean running = false;
    /**
     * Will be set to true whenever the endpoint is paused.
     */
    protected volatile boolean paused = false;
    /**
     * Track the initialization state of the endpoint.
     */
    protected boolean initialized = false;
    /**
     * Current worker threads busy count.
     */
    protected int curThreadsBusy = 0;
    /**
     * Current worker threads count.
     */
    protected int curThreads = 0;
    /**
     * Sequence number used to generate thread names.
     */
    protected int sequence = 0;
    protected NioSelectorPool selectorPool = new NioSelectorPool();
    /**
     * Server socket "pointer".
     */
    protected ServerSocketChannel serverSock = null;
    /**
     * use send file
     */
    protected boolean useSendfile = true;
    /**
     * The size of the OOM parachute.
     */
    protected int oomParachute = 1024 * 1024;
    /**
     * The oom parachute, when an OOM error happens,
     * will release the data, giving the JVM instantly
     * a chunk of data to be able to recover with.
     */
    protected byte[] oomParachuteData = null;
    /**
     * Keep track of how many threads are in use
     */
    protected AtomicInteger activeSocketProcessors = new AtomicInteger(0);
    /**
     *
     */
    protected volatile CountDownLatch stopLatch = null;
    /**
     * External Executor based thread pool.
     */
    protected Executor executor = null;
    protected boolean useExecutor = true;
    /**
     * Maximum amount of worker threads.
     */
    protected int maxThreads = 200;
    /**
     * Priority of the worker threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    /**
     * Priority of the acceptor threads.
     */
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;


    // ------------------------------------------------------------- Properties
    /**
     * Priority of the poller threads.
     */
    protected int pollerThreadPriority = Thread.NORM_PRIORITY;
    /**
     * Server socket port.
     */
    protected int port;
    /**
     * Address for the server socket.
     */
    protected InetAddress address;
    /**
     * Handling of accepted sockets.
     */
    protected Handler handler = null;
    /**
     * Allows the server developer to specify the backlog that
     * should be used for server sockets. By default, this value
     * is 100.
     */
    protected int backlog = 100;
    protected SocketProperties socketProperties = new SocketProperties();
    /**
     * Cache for SocketProcessor objects
     */
    protected ConcurrentLinkedQueue<SocketProcessor> processorCache = new ConcurrentLinkedQueue<SocketProcessor>()
    {
        protected AtomicInteger size = new AtomicInteger(0);

        public boolean offer(SocketProcessor sc)
        {
            sc.reset(null, null);
            boolean offer = socketProperties.getProcessorCache() == -1 ? true : size.get() < socketProperties.getProcessorCache();
            //avoid over growing our cache or add after we have stopped
            if (running && (!paused) && (offer))
            {
                boolean result = super.offer(sc);
                if (result)
                {
                    size.incrementAndGet();
                }
                return result;
            } else return false;
        }

        public SocketProcessor poll()
        {
            SocketProcessor result = super.poll();
            if (result != null)
            {
                size.decrementAndGet();
            }
            return result;
        }

        public void clear()
        {
            super.clear();
            size.set(0);
        }
    };
    /**
     * Cache for key attachment objects
     */
    protected ConcurrentLinkedQueue<KeyAttachment> keyCache = new ConcurrentLinkedQueue<KeyAttachment>()
    {
        protected AtomicInteger size = new AtomicInteger(0);

        public boolean offer(KeyAttachment ka)
        {
            ka.reset();
            boolean offer = socketProperties.getKeyCache() == -1 ? true : size.get() < socketProperties.getKeyCache();
            //avoid over growing our cache or add after we have stopped
            if (running && (!paused) && (offer))
            {
                boolean result = super.offer(ka);
                if (result)
                {
                    size.incrementAndGet();
                }
                return result;
            } else return false;
        }

        public KeyAttachment poll()
        {
            KeyAttachment result = super.poll();
            if (result != null)
            {
                size.decrementAndGet();
            }
            return result;
        }

        public void clear()
        {
            super.clear();
            size.set(0);
        }
    };
    /**
     * Cache for poller events
     */
    protected ConcurrentLinkedQueue<PollerEvent> eventCache = new ConcurrentLinkedQueue<PollerEvent>()
    {
        protected AtomicInteger size = new AtomicInteger(0);

        public boolean offer(PollerEvent pe)
        {
            pe.reset();
            boolean offer = socketProperties.getEventCache() == -1 ? true : size.get() < socketProperties.getEventCache();
            //avoid over growing our cache or add after we have stopped
            if (running && (!paused) && (offer))
            {
                boolean result = super.offer(pe);
                if (result)
                {
                    size.incrementAndGet();
                }
                return result;
            } else return false;
        }

        public PollerEvent poll()
        {
            PollerEvent result = super.poll();
            if (result != null)
            {
                size.decrementAndGet();
            }
            return result;
        }

        public void clear()
        {
            super.clear();
            size.set(0);
        }
    };
    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     */
    protected ConcurrentLinkedQueue<NioChannel> nioChannels = new ConcurrentLinkedQueue<NioChannel>()
    {
        protected AtomicInteger size = new AtomicInteger(0);
        protected AtomicInteger bytes = new AtomicInteger(0);

        public boolean offer(NioChannel socket)
        {
            boolean offer = socketProperties.getBufferPool() == -1 ? true : size.get() < socketProperties.getBufferPool();
            offer = offer && (socketProperties.getBufferPoolSize() == -1 ? true : (bytes.get() + socket.getBufferSize()) < socketProperties.getBufferPoolSize());
            //avoid over growing our cache or add after we have stopped
            if (running && (!paused) && (offer))
            {
                boolean result = super.offer(socket);
                if (result)
                {
                    size.incrementAndGet();
                    bytes.addAndGet(socket.getBufferSize());
                }
                return result;
            } else return false;
        }

        public NioChannel poll()
        {
            NioChannel result = super.poll();
            if (result != null)
            {
                size.decrementAndGet();
                bytes.addAndGet(-result.getBufferSize());
            }
            return result;
        }

        public void clear()
        {
            super.clear();
            size.set(0);
            bytes.set(0);
        }
    };
    /**
     * The default is true - the created threads will be
     * in daemon mode. If set to false, the control thread
     * will not be daemon - and will keep the process alive.
     */
    protected boolean daemon = true;
    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    protected String name = "TP";
    /**
     * Allow comet request handling.
     */
    protected boolean useComet = true;
    /**
     * Acceptor thread count.
     */
    protected int acceptorThreadCount = 1;
    /**
     * Poller thread count.
     */
    protected int pollerThreadCount = Runtime.getRuntime().availableProcessors();
    protected long selectorTimeout = 1000;
    /**
     * The socket poller.
     */
    protected Poller[] pollers = null;
    protected AtomicInteger pollerRotater = new AtomicInteger(0);
    // --------------------  SSL related properties --------------------
    protected String truststoreFile = System.getProperty("javax.net.ssl.trustStore");
    protected String truststorePass = System.getProperty("javax.net.ssl.trustStorePassword");
    protected String truststoreType = System.getProperty("javax.net.ssl.trustStoreType");
    protected String keystoreFile = System.getProperty("user.home") + "/.keystore";
    protected String algorithm = KeyManagerFactory.getDefaultAlgorithm();
    protected String clientAuth = "false";
    protected String keystorePass = "changeit";
    protected String keystoreType = "JKS";
    protected String sslProtocol = "TLS";
    protected String sslEnabledProtocols = null; //"TLSv1,SSLv3,SSLv2Hello"
    protected String[] sslEnabledProtocolsarr = new String[0];
    protected String ciphers = null;
    protected String[] ciphersarr = new String[0];
    protected int sessionCacheSize = 0;
    protected int sessionTimeout = 86400;
    /**
     * SSL engine.
     */
    protected boolean SSLEnabled = false;
    protected boolean secure = false;
    protected SSLContext sslContext = null;
    /**
     * Keep track of OOM warning messages.
     */
    long lastParachuteCheck = System.currentTimeMillis();
    String keyAlias = null;

    public Executor getExecutor()
    {
        return executor;
    }

    public void setExecutor(Executor executor)
    {
        this.executor = executor;
    }

    public boolean getUseExecutor()
    {
        return useExecutor || (executor != null);
    }

    public void setUseExecutor(boolean useexec)
    {
        useExecutor = useexec;
    }

    public int getMaxThreads()
    {
        if (running && getUseExecutor() && executor != null)
        {
            if (executor instanceof ThreadPoolExecutor)
            {
                return ((ThreadPoolExecutor) executor).getMaximumPoolSize();
            } else
            {
                return -1;
            }
        } else
        {
            return maxThreads;
        }
    }

    public void setMaxThreads(int maxThreads)
    {
        this.maxThreads = maxThreads;
        if (running)
        {
            if (getUseExecutor() && executor != null)
            {
                if (executor instanceof ThreadPoolExecutor)
                {
                    ((ThreadPoolExecutor) executor).setMaximumPoolSize(maxThreads);
                }
            } else if (workers != null)
            {
                synchronized (workers)
                {
                    workers.resize(maxThreads);
                }
            }
        }
    }

    public int getThreadPriority()
    {
        return threadPriority;
    }

    public void setThreadPriority(int threadPriority)
    {
        this.threadPriority = threadPriority;
    }

    public int getAcceptorThreadPriority()
    {
        return acceptorThreadPriority;
    }

    public void setAcceptorThreadPriority(int acceptorThreadPriority)
    {
        this.acceptorThreadPriority = acceptorThreadPriority;
    }

    public int getPollerThreadPriority()
    {
        return pollerThreadPriority;
    }

    public void setPollerThreadPriority(int pollerThreadPriority)
    {
        this.pollerThreadPriority = pollerThreadPriority;
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(int port)
    {
        this.port = port;
    }

    public InetAddress getAddress()
    {
        return address;
    }

    public void setAddress(InetAddress address)
    {
        this.address = address;
    }

    public Handler getHandler()
    {
        return handler;
    }

    public void setHandler(Handler handler)
    {
        this.handler = handler;
    }

    public int getBacklog()
    {
        return backlog;
    }

    public void setBacklog(int backlog)
    {
        if (backlog > 0) this.backlog = backlog;
    }

    /**
     * Socket TCP no delay.
     */
    public boolean getTcpNoDelay()
    {
        return socketProperties.getTcpNoDelay();
    }

    public void setTcpNoDelay(boolean tcpNoDelay)
    {
        socketProperties.setTcpNoDelay(tcpNoDelay);
    }

    /**
     * Socket linger.
     */
    public int getSoLinger()
    {
        return socketProperties.getSoLingerTime();
    }

    public void setSoLinger(int soLinger)
    {
        socketProperties.setSoLingerTime(soLinger);
        socketProperties.setSoLingerOn(soLinger >= 0);
    }

    /**
     * Socket timeout.
     */
    public int getSoTimeout()
    {
        return socketProperties.getSoTimeout();
    }

    public void setSoTimeout(int soTimeout)
    {
        socketProperties.setSoTimeout(soTimeout);
    }

    public boolean getDaemon()
    {
        return daemon;
    }

    public void setDaemon(boolean b)
    {
        daemon = b;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public boolean getUseComet()
    {
        return useComet;
    }

    public void setUseComet(boolean useComet)
    {
        this.useComet = useComet;
    }

    public int getAcceptorThreadCount()
    {
        return acceptorThreadCount;
    }

    public void setAcceptorThreadCount(int acceptorThreadCount)
    {
        this.acceptorThreadCount = acceptorThreadCount;
    }

    public int getPollerThreadCount()
    {
        return pollerThreadCount;
    }

    public void setPollerThreadCount(int pollerThreadCount)
    {
        this.pollerThreadCount = pollerThreadCount;
    }

    public long getSelectorTimeout()
    {
        return this.selectorTimeout;
    }

    public void setSelectorTimeout(long timeout)
    {
        this.selectorTimeout = timeout;
    }

    /**
     * Return an available poller in true round robin fashion
     */
    public Poller getPoller0()
    {
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }

    /**
     * Dummy maxSpareThreads property.
     */
    public int getMaxSpareThreads()
    {
        return Math.min(getMaxThreads(), 5);
    }

    /**
     * Dummy minSpareThreads property.
     */
    public int getMinSpareThreads()
    {
        return Math.min(getMaxThreads(), 5);
    }

    /**
     * Generic properties, introspected
     */
    public boolean setProperty(String name, String value)
    {
        final String selectorPoolName = "selectorPool.";
        final String socketName = "socket.";
        try
        {
            if (name.startsWith(selectorPoolName))
            {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else if (name.startsWith(socketName))
            {
                return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
            } else
            {
                return IntrospectionUtils.setProperty(this, name, value, false);
            }
        }
        catch (Exception x)
        {
            log.error("Unable to set attribute \"" + name + "\" to \"" + value + "\"", x);
            return false;
        }
    }

    public String adjustRelativePath(String path, String relativeTo)
    {
        File f = new File(path);
        if (!f.isAbsolute())
        {
            path = relativeTo + File.separator + path;
            f = new File(path);
        }
        if (!f.exists())
        {
            log.warn("configured file:[" + path + "] does not exist.");
        }
        return path;
    }

    public String defaultIfNull(String val, String defaultValue)
    {
        if (val == null) return defaultValue;
        else return val;
    }

    public String getTruststoreFile()
    {
        return truststoreFile;
    }

    public void setTruststoreFile(String s)
    {
        s = adjustRelativePath(s, System.getProperty("catalina.base"));
        this.truststoreFile = s;
    }

    public String getTruststorePass()
    {
        return truststorePass;
    }

    public void setTruststorePass(String truststorePass)
    {
        this.truststorePass = truststorePass;
    }

    public String getTruststoreType()
    {
        return truststoreType;
    }

    public void setTruststoreType(String truststoreType)
    {
        this.truststoreType = truststoreType;
    }

    public String getKeystoreFile()
    {
        return keystoreFile;
    }

    public void setKeystoreFile(String s)
    {
        s = adjustRelativePath(s, System.getProperty("catalina.base"));
        this.keystoreFile = s;
    }

    public String getKeystore()
    {
        return getKeystoreFile();
    }

    public void setKeystore(String s)
    {
        setKeystoreFile(s);
    }

    public String getKeyAlias()
    {
        return keyAlias;
    }

    public void setKeyAlias(String s)
    {
        keyAlias = s;
    }

    public String getAlgorithm()
    {
        return algorithm;
    }

    public void setAlgorithm(String s)
    {
        this.algorithm = s;
    }

    public String getClientAuth()
    {
        return clientAuth;
    }

    public void setClientAuth(String s)
    {
        this.clientAuth = s;
    }

    public String getKeystorePass()
    {
        return keystorePass;
    }

    public void setKeystorePass(String s)
    {
        this.keystorePass = s;
    }

    public String getKeystoreType()
    {
        return keystoreType;
    }

    public void setKeystoreType(String s)
    {
        this.keystoreType = s;
    }

    public String getSslProtocol()
    {
        return sslProtocol;
    }

    public void setSslProtocol(String s)
    {
        sslProtocol = s;
    }

    public void setSslEnabledProtocols(String s)
    {
        this.sslEnabledProtocols = s;
        StringTokenizer t = new StringTokenizer(s, ",");
        sslEnabledProtocolsarr = new String[t.countTokens()];
        for (int i = 0; i < sslEnabledProtocolsarr.length; i++) sslEnabledProtocolsarr[i] = t.nextToken();
    }

    public String getCiphers()
    {
        return ciphers;
    }

    public void setCiphers(String s)
    {
        ciphers = s;
        if (s == null) ciphersarr = new String[0];
        else
        {
            StringTokenizer t = new StringTokenizer(s, ",");
            ciphersarr = new String[t.countTokens()];
            for (int i = 0; i < ciphersarr.length; i++)
            {
                ciphersarr[i] = t.nextToken().trim();
            }
        }
    }

    public int getSessionCacheSize()
    {
        return sessionCacheSize;
    }

    public void setSessionCacheSize(int i)
    {
        sessionCacheSize = i;
    }

    public int getSessionTimeout()
    {
        return sessionTimeout;
    }

    public void setSessionTimeout(int i)
    {
        sessionTimeout = i;
    }

    public boolean isSSLEnabled()
    {
        return SSLEnabled;
    }

    public void setSSLEnabled(boolean SSLEnabled)
    {
        this.SSLEnabled = SSLEnabled;
    }

    public boolean getSecure()
    {
        return secure;
    }

    public void setSecure(boolean b)
    {
        secure = b;
    }

    public SSLContext getSSLContext()
    {
        return sslContext;
    }

    public void setSSLContext(SSLContext c)
    {
        sslContext = c;
    }

    protected void checkParachute()
    {
        boolean para = reclaimParachute(false);
        if (!para && (System.currentTimeMillis() - lastParachuteCheck) > 10000)
        {
            try
            {
                log.fatal(oomParachuteMsg);
            }
            catch (Throwable t)
            {
                System.err.println(oomParachuteMsg);
            }
            lastParachuteCheck = System.currentTimeMillis();
        }
    }

    protected boolean reclaimParachute(boolean force)
    {
        if (oomParachuteData != null) return true;
        if (oomParachute > 0 && (force || (Runtime.getRuntime().freeMemory() > (oomParachute * 2))))
            oomParachuteData = new byte[oomParachute];
        return oomParachuteData != null;
    }

    protected void releaseCaches()
    {
        this.keyCache.clear();
        this.nioChannels.clear();
        this.processorCache.clear();
        if (handler != null) handler.releaseCaches();

    }

    /**
     * Number of keepalive sockets.
     */
    public int getKeepAliveCount()
    {
        if (pollers == null)
        {
            return 0;
        } else
        {
            int sum = 0;
            for (int i = 0; i < pollers.length; i++)
            {
                sum += pollers[i].selector.keys().size();
            }
            return sum;
        }
    }

    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount()
    {
        if (executor != null)
        {
            if (executor instanceof ThreadPoolExecutor)
            {
                return ((ThreadPoolExecutor) executor).getPoolSize();
            } else
            {
                return -1;
            }
        } else
        {
            return curThreads;
        }
    }

    // --------------------------------------------------------- OOM Parachute Methods

    /**
     * Return the amount of threads that are in use
     *
     * @return the amount of threads that are in use
     */
    public int getCurrentThreadsBusy()
    {
        if (executor != null)
        {
            if (executor instanceof ThreadPoolExecutor)
            {
                return ((ThreadPoolExecutor) executor).getActiveCount();
            } else
            {
                return -1;
            }
        } else
        {
            return workers != null ? curThreads - workers.size() : 0;
        }
    }

    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is running, false otherwise
     */
    public boolean isRunning()
    {
        return running;
    }

    /**
     * Return the state of the endpoint.
     *
     * @return true if the endpoint is paused, false otherwise
     */
    public boolean isPaused()
    {
        return paused;
    }

    // --------------------------------------------------------- Public Methods

    /**
     * Initialize the endpoint.
     */
    public void init()
            throws Exception
    {

        if (initialized)
            return;

        serverSock = ServerSocketChannel.open();
        serverSock.socket().setPerformancePreferences(socketProperties.getPerformanceConnectionTime(),
                socketProperties.getPerformanceLatency(),
                socketProperties.getPerformanceBandwidth());
        InetSocketAddress addr = (address != null ? new InetSocketAddress(address, port) : new InetSocketAddress(port));
        serverSock.socket().bind(addr, backlog);
        serverSock.configureBlocking(true); //mimic APR behavior
        serverSock.socket().setSoTimeout(getSocketProperties().getSoTimeout());

        // Initialize thread count defaults for acceptor, poller
        if (acceptorThreadCount == 0)
        {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            acceptorThreadCount = 1;
        }
        if (pollerThreadCount <= 0)
        {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        stopLatch = new CountDownLatch(pollerThreadCount);

        // Initialize SSL if needed
        if (isSSLEnabled())
        {
            // Initialize SSL
            char[] passphrase = getKeystorePass().toCharArray();

            char[] tpassphrase = (getTruststorePass() != null) ? getTruststorePass().toCharArray() : passphrase;
            String ttype = (getTruststoreType() != null) ? getTruststoreType() : getKeystoreType();

            KeyStore ks = KeyStore.getInstance(getKeystoreType());
            ks.load(new FileInputStream(getKeystoreFile()), passphrase);
            KeyStore ts = null;
            if (getTruststoreFile() == null)
            {
                //no op, same as for BIO connector
            } else
            {
                ts = KeyStore.getInstance(ttype);
                ts.load(new FileInputStream(getTruststoreFile()), tpassphrase);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(getAlgorithm());
            kmf.init(ks, passphrase);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(getAlgorithm());
            tmf.init(ts);

            sslContext = SSLContext.getInstance(getSslProtocol());
            sslContext.init(wrap(kmf.getKeyManagers()), tmf.getTrustManagers(), null);
            SSLSessionContext sessionContext =
                    sslContext.getServerSessionContext();
            if (sessionContext != null)
            {
                sessionContext.setSessionCacheSize(sessionCacheSize);
                sessionContext.setSessionTimeout(sessionTimeout);
            }
        }

        if (oomParachute > 0) reclaimParachute(true);
        selectorPool.open();
        initialized = true;

    }

    public KeyManager[] wrap(KeyManager[] managers)
    {
        if (managers == null) return null;
        KeyManager[] result = new KeyManager[managers.length];
        for (int i = 0; i < result.length; i++)
        {
            if (managers[i] instanceof X509KeyManager && getKeyAlias() != null)
            {
                result[i] = new NioX509KeyManager((X509KeyManager) managers[i], getKeyAlias());
            } else
            {
                result[i] = managers[i];
            }
        }
        return result;
    }

    /**
     * Start the NIO endpoint, creating acceptor, poller threads.
     */
    public void start()
            throws Exception
    {
        // Initialize socket if not done before
        if (!initialized)
        {
            init();
        }
        if (!running)
        {
            running = true;
            paused = false;

            // Create worker collection
            if (getUseExecutor())
            {
                if (executor == null)
                {
                    TaskQueue taskqueue = new TaskQueue();
                    TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-");
                    executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), 60, TimeUnit.SECONDS, taskqueue, tf);
                    taskqueue.setParent((ThreadPoolExecutor) executor, this);
                }
            } else if (executor == null)
            {//avoid two thread pools being created
                workers = new WorkerStack(maxThreads);
            }

            // Start poller threads
            pollers = new Poller[getPollerThreadCount()];
            for (int i = 0; i < pollers.length; i++)
            {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-" + i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            // Start acceptor threads
            for (int i = 0; i < acceptorThreadCount; i++)
            {
                Thread acceptorThread = new Thread(new Acceptor(), getName() + "-Acceptor-" + i);
                acceptorThread.setPriority(threadPriority);
                acceptorThread.setDaemon(daemon);
                acceptorThread.start();
            }
        }
    }

    /**
     * Pause the endpoint, which will make it stop accepting new sockets.
     */
    public void pause()
    {
        if (running && !paused)
        {
            paused = true;
            unlockAccept();
        }
    }

    /**
     * Resume the endpoint, which will make it start accepting new sockets
     * again.
     */
    public void resume()
    {
        if (running)
        {
            paused = false;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     */
    public void stop()
    {
        if (running)
        {
            running = false;
            unlockAccept();
            for (int i = 0; pollers != null && i < pollers.length; i++)
            {
                if (pollers[i] == null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try
            {
                stopLatch.await(selectorTimeout + 100, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException ignore)
            {
            }
        }
        eventCache.clear();
        keyCache.clear();
        nioChannels.clear();
        processorCache.clear();
        if (executor != null)
        {
            if (executor instanceof ThreadPoolExecutor)
            {
                //this is our internal one, so we need to shut it down
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                TaskQueue queue = (TaskQueue) tpe.getQueue();
                queue.setParent(null, null);
            }
            executor = null;
        }

    }

    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    public void destroy() throws Exception
    {
        if (log.isDebugEnabled())
        {
            log.debug("Destroy initiated for " + new InetSocketAddress(address, port));
        }
        if (running)
        {
            stop();
        }
        // Close server socket
        serverSock.socket().close();
        serverSock.close();
        serverSock = null;
        sslContext = null;
        initialized = false;
        releaseCaches();
        selectorPool.close();
        if (log.isDebugEnabled())
        {
            log.debug("Destroy completed for " + new InetSocketAddress(address, port));
        }
    }

    /**
     * Get a sequence number used for thread naming.
     */
    protected int getSequence()
    {
        return sequence++;
    }

    public int getWriteBufSize()
    {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize()
    {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool()
    {
        return selectorPool;
    }

    public void setSelectorPool(NioSelectorPool selectorPool)
    {
        this.selectorPool = selectorPool;
    }


    // ------------------------------------------------------ Protected Methods

    public SocketProperties getSocketProperties()
    {
        return socketProperties;
    }

    public void setSocketProperties(SocketProperties socketProperties)
    {
        this.socketProperties = socketProperties;
    }

    public boolean getUseSendfile()
    {
        return useSendfile;
    }

    public void setUseSendfile(boolean useSendfile)
    {
        this.useSendfile = useSendfile;
    }

    public int getOomParachute()
    {
        return oomParachute;
    }

    public void setOomParachute(int oomParachute)
    {
        this.oomParachute = oomParachute;
    }

    public byte[] getOomParachuteData()
    {
        return oomParachuteData;
    }

    public void setOomParachuteData(byte[] oomParachuteData)
    {
        this.oomParachuteData = oomParachuteData;
    }

    /**
     * Unlock the server socket accept using a bogus connection.
     */
    protected void unlockAccept()
    {
        java.net.Socket s = null;
        InetSocketAddress saddr = null;
        try
        {
            // Need to create a connection to unlock the accept();
            if (address == null)
            {
                saddr = new InetSocketAddress("localhost", port);
            } else
            {
                saddr = new InetSocketAddress(address, port);
            }
            s = new java.net.Socket();
            s.setSoTimeout(getSocketProperties().getSoTimeout());
            s.setSoLinger(getSocketProperties().getSoLingerOn(), getSocketProperties().getSoLingerTime());
            if (log.isDebugEnabled())
            {
                log.debug("About to unlock socket for:" + saddr);
            }
            s.connect(saddr, getSocketProperties().getUnlockTimeout());
            if (log.isDebugEnabled())
            {
                log.debug("Socket unlock completed for:" + saddr);
            }
        }
        catch (Exception e)
        {
            if (log.isDebugEnabled())
            {
                log.debug(sm.getString("endpoint.debug.unlock", "" + port), e);
            }
        }
        finally
        {
            if (s != null)
            {
                try
                {
                    s.close();
                }
                catch (Exception e)
                {
                    // Ignore
                }
            }
        }
    }


    /**
     * Process the specified connection.
     */
    protected boolean setSocketOptions(SocketChannel socket)
    {
        // Process the connection
        try
        {
            //disable blocking, APR style, we are gonna be polling it
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.poll();
            if (channel == null)
            {
                // SSL setup
                if (sslContext != null)
                {
                    SSLEngine engine = createSSLEngine();
                    int appbufsize = engine.getSession().getApplicationBufferSize();
                    NioBufferHandler bufhandler = new NioBufferHandler(Math.max(appbufsize, socketProperties.getAppReadBufSize()),
                            Math.max(appbufsize, socketProperties.getAppWriteBufSize()),
                            socketProperties.getDirectBuffer());
                    channel = new SecureNioChannel(socket, engine, bufhandler, selectorPool);
                } else
                {
                    // normal tcp setup
                    NioBufferHandler bufhandler = new NioBufferHandler(socketProperties.getAppReadBufSize(),
                            socketProperties.getAppWriteBufSize(),
                            socketProperties.getDirectBuffer());

                    channel = new NioChannel(socket, bufhandler);
                }
            } else
            {
                channel.setIOChannel(socket);
                if (channel instanceof SecureNioChannel)
                {
                    SSLEngine engine = createSSLEngine();
                    ((SecureNioChannel) channel).reset(engine);
                } else
                {
                    channel.reset();
                }
            }
            getPoller0().register(channel);
        }
        catch (Throwable t)
        {
            try
            {
                log.error("", t);
            }
            catch (Throwable tt)
            {
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }

    protected SSLEngine createSSLEngine()
    {
        SSLEngine engine = sslContext.createSSLEngine();
        if ("false".equals(getClientAuth()))
        {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        } else if ("true".equals(getClientAuth()) || "yes".equals(getClientAuth()))
        {
            engine.setNeedClientAuth(true);
        } else if ("want".equals(getClientAuth()))
        {
            engine.setWantClientAuth(true);
        }
        engine.setUseClientMode(false);
        if (ciphersarr.length > 0) engine.setEnabledCipherSuites(ciphersarr);
        if (sslEnabledProtocolsarr.length > 0)
        {
            engine.setEnabledProtocols(sslEnabledProtocolsarr);
        } else
        {
            // Filter out the insecure protocols from the defaults
            engine.setEnabledProtocols(JSSESocketFactory.filterInsecureProtocols(
                    engine.getEnabledProtocols()));
        }

        return engine;
    }


    /**
     * Returns true if a worker thread is available for processing.
     *
     * @return boolean
     */
    protected boolean isWorkerAvailable()
    {
        if (executor != null)
        {
            return true;
        } else
        {
            if (workers.size() > 0)
            {
                return true;
            }
            if ((maxThreads > 0) && (curThreads < maxThreads))
            {
                return true;
            } else
            {
                if (maxThreads < 0)
                {
                    return true;
                } else
                {
                    return false;
                }
            }
        }
    }

    /**
     * Create (or allocate) and return an available processor for use in
     * processing a specific HTTP request, if possible.  If the maximum
     * allowed processors have already been created and are in use, return
     * <code>null</code> instead.
     */
    protected Worker createWorkerThread()
    {

        synchronized (workers)
        {
            if (workers.size() > 0)
            {
                curThreadsBusy++;
                return (workers.pop());
            }
            if ((maxThreads > 0) && (curThreads < maxThreads))
            {
                curThreadsBusy++;
                if (curThreadsBusy == maxThreads)
                {
                    log.info(sm.getString("endpoint.info.maxThreads",
                            Integer.toString(maxThreads), address,
                            Integer.toString(port)));
                }
                return (newWorkerThread());
            } else
            {
                if (maxThreads < 0)
                {
                    curThreadsBusy++;
                    return (newWorkerThread());
                } else
                {
                    return (null);
                }
            }
        }
    }


    /**
     * Create and return a new processor suitable for processing HTTP
     * requests and returning the corresponding responses.
     */
    protected Worker newWorkerThread()
    {

        Worker workerThread = new Worker();
        workerThread.start();
        return (workerThread);

    }


    /**
     * Return a new worker thread, and block while to worker is available.
     */
    protected Worker getWorkerThread()
    {
        // Allocate a new worker thread
        synchronized (workers)
        {
            Worker workerThread;
            while ((workerThread = createWorkerThread()) == null)
            {
                try
                {
                    workers.wait();
                }
                catch (InterruptedException e)
                {
                    // Ignore
                }
            }
            return workerThread;
        }
    }


    /**
     * Recycle the specified Processor so that it can be used again.
     *
     * @param workerThread The processor to be recycled
     */
    protected void recycleWorkerThread(Worker workerThread)
    {
        synchronized (workers)
        {
            workers.push(workerThread);
            curThreadsBusy--;
            workers.notify();
        }
    }

    /**
     * Process given socket.
     */
    protected boolean processSocket(NioChannel socket)
    {
        return processSocket(socket, null);
    }


    /**
     * Process given socket for an event.
     */
    protected boolean processSocket(NioChannel socket, SocketStatus status)
    {
        return processSocket(socket, status, true);
    }

    protected boolean processSocket(NioChannel socket, SocketStatus status, boolean dispatch)
    {
        try
        {
            KeyAttachment attachment = (KeyAttachment) socket.getAttachment(false);
            attachment.setCometNotify(false); //will get reset upon next reg
            if (executor == null)
            {
                getWorkerThread().assign(socket, status);
            } else
            {
                SocketProcessor sc = processorCache.poll();
                if (sc == null) sc = new SocketProcessor(socket, status);
                else sc.reset(socket, status);
                if (dispatch) executor.execute(sc);
                else sc.run();
            }
        }
        catch (Throwable t)
        {
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    // --------------------------------------------------- Acceptor Inner Class


    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler
    {
        public SocketState process(NioChannel socket);

        public SocketState event(NioChannel socket, SocketStatus status);

        public void releaseCaches();

        public void release(NioChannel socket);

        public enum SocketState
        {
            OPEN, CLOSED, LONG
        }
    }


    // ----------------------------------------------------- Poller Inner Classes

    // ----------------------------------------------------- Key Attachment Class
    public static class KeyAttachment
    {

        protected Poller poller = null;
        protected int interestOps = 0;
        protected long lastAccess = -1;
        protected boolean currentAccess = false;
        protected boolean comet = false;
        protected int cometOps = SelectionKey.OP_READ;
        protected boolean cometNotify = false;
        protected long timeout = -1;
        protected boolean error = false;
        protected NioChannel channel = null;
        protected CountDownLatch readLatch = null;
        protected CountDownLatch writeLatch = null;
        protected long lastRegistered = 0;
        protected SendfileData sendfileData = null;

        public KeyAttachment()
        {

        }

        public void reset(Poller poller, NioChannel channel, long soTimeout)
        {
            this.channel = channel;
            this.poller = poller;
            lastAccess = System.currentTimeMillis();
            currentAccess = false;
            comet = false;
            timeout = soTimeout;
            error = false;
            lastRegistered = 0;
            sendfileData = null;
            if (readLatch != null) try
            {
                for (int i = 0; i < (int) readLatch.getCount(); i++) readLatch.countDown();
            }
            catch (Exception ignore)
            {
            }
            readLatch = null;
            if (writeLatch != null) try
            {
                for (int i = 0; i < (int) writeLatch.getCount(); i++) writeLatch.countDown();
            }
            catch (Exception ignore)
            {
            }
            writeLatch = null;
            cometNotify = false;
            cometOps = SelectionKey.OP_READ;
            sendfileData = null;
        }

        public void reset()
        {
            reset(null, null, -1);
        }

        public Poller getPoller()
        {
            return poller;
        }

        public void setPoller(Poller poller)
        {
            this.poller = poller;
        }

        public long getLastAccess()
        {
            return lastAccess;
        }

        public void access()
        {
            access(System.currentTimeMillis());
        }

        public void access(long access)
        {
            lastAccess = access;
        }

        public boolean getComet()
        {
            return comet;
        }

        public void setComet(boolean comet)
        {
            this.comet = comet;
        }

        public boolean getCometNotify()
        {
            return cometNotify;
        }

        public void setCometNotify(boolean notify)
        {
            this.cometNotify = notify;
        }

        public int getCometOps()
        {
            return cometOps;
        }

        public void setCometOps(int ops)
        {
            this.cometOps = ops;
        }

        public boolean getCurrentAccess()
        {
            return currentAccess;
        }

        public void setCurrentAccess(boolean access)
        {
            currentAccess = access;
        }

        public long getTimeout()
        {
            return this.timeout;
        }

        public void setTimeout(long timeout)
        {
            this.timeout = timeout;
        }

        public boolean getError()
        {
            return error;
        }

        public void setError(boolean error)
        {
            this.error = error;
        }

        public NioChannel getChannel()
        {
            return channel;
        }

        public void setChannel(NioChannel channel)
        {
            this.channel = channel;
        }

        public int interestOps()
        {
            return interestOps;
        }

        public int interestOps(int ops)
        {
            this.interestOps = ops;
            return ops;
        }

        ;

        public CountDownLatch getReadLatch()
        {
            return readLatch;
        }

        public CountDownLatch getWriteLatch()
        {
            return writeLatch;
        }

        protected CountDownLatch resetLatch(CountDownLatch latch)
        {
            if (latch == null || latch.getCount() == 0) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }

        public void resetReadLatch()
        {
            readLatch = resetLatch(readLatch);
        }

        public void resetWriteLatch()
        {
            writeLatch = resetLatch(writeLatch);
        }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt)
        {
            if (latch == null || latch.getCount() == 0)
            {
                return new CountDownLatch(cnt);
            } else throw new IllegalStateException("Latch must be at count 0 or null.");
        }

        public void startReadLatch(int cnt)
        {
            readLatch = startLatch(readLatch, cnt);
        }

        public void startWriteLatch(int cnt)
        {
            writeLatch = startLatch(writeLatch, cnt);
        }

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException
        {
            if (latch == null) throw new IllegalStateException("Latch cannot be null");
            latch.await(timeout, unit);
        }

        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException
        {
            awaitLatch(readLatch, timeout, unit);
        }

        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException
        {
            awaitLatch(writeLatch, timeout, unit);
        }

        public long getLastRegistered()
        {
            return lastRegistered;
        }

        public void setLastRegistered(long reg)
        {
            lastRegistered = reg;
        }

        public SendfileData getSendfileData()
        {
            return this.sendfileData;
        }

        public void setSendfileData(SendfileData sf)
        {
            this.sendfileData = sf;
        }
    }

    // ---------------------------------------------- TaskQueue Inner Class
    public static class TaskQueue extends LinkedBlockingQueue<Runnable>
    {
        ThreadPoolExecutor parent = null;
        NioEndpoint endpoint = null;

        public TaskQueue()
        {
            super();
        }

        public TaskQueue(int initialCapacity)
        {
            super(initialCapacity);
        }

        public TaskQueue(Collection<? extends Runnable> c)
        {
            super(c);
        }


        public void setParent(ThreadPoolExecutor tp, NioEndpoint ep)
        {
            parent = tp;
            this.endpoint = ep;
        }

        public boolean offer(Runnable o)
        {
            //we can't do any checks
            if (parent == null) return super.offer(o);
            //we are maxed out on threads, simply queue the object
            if (parent.getPoolSize() == parent.getMaximumPoolSize()) return super.offer(o);
            //we have idle threads, just add it to the queue
            //this is an approximation, so it could use some tuning
            if (endpoint.activeSocketProcessors.get() < (parent.getPoolSize())) return super.offer(o);
            //if we have less threads than maximum force creation of a new thread
            if (parent.getPoolSize() < parent.getMaximumPoolSize()) return false;
            //if we reached here, we need to add it to the queue
            return super.offer(o);
        }
    }

    /**
     * SendfileData class.
     */
    public static class SendfileData
    {
        // File
        public String fileName;
        public FileChannel fchannel;
        public long pos;
        public long length;
        // KeepAlive flag
        public boolean keepAlive;
    }
    // ----------------------------------------------------- Worker Inner Class

    /**
     * Server socket acceptor thread.
     */
    protected class Acceptor implements Runnable
    {
        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run()
        {
            // Loop until we receive a shutdown command
            while (running)
            {
                // Loop if endpoint is paused
                while (paused)
                {
                    try
                    {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e)
                    {
                        // Ignore
                    }
                }
                try
                {
                    // Accept the next incoming connection from the server socket
                    SocketChannel socket = serverSock.accept();
                    // Hand this socket off to an appropriate processor
                    //TODO FIXME - this is currently a blocking call, meaning we will be blocking
                    //further accepts until there is a thread available.
                    if (running && (!paused) && socket != null)
                    {
                        //processSocket(socket);
                        if (!setSocketOptions(socket))
                        {
                            try
                            {
                                socket.socket().close();
                                socket.close();
                            }
                            catch (IOException ix)
                            {
                                if (log.isDebugEnabled())
                                    log.debug("", ix);
                            }
                        }
                    }
                }
                catch (SocketTimeoutException sx)
                {
                    //normal condition
                }
                catch (IOException x)
                {
                    if (running) log.error(sm.getString("endpoint.accept.fail"), x);
                }
                catch (OutOfMemoryError oom)
                {
                    try
                    {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }
                    catch (Throwable oomt)
                    {
                        try
                        {
                            try
                            {
                                System.err.println(oomParachuteMsg);
                                oomt.printStackTrace();
                            }
                            catch (Throwable letsHopeWeDontGetHere)
                            {
                            }
                        }
                        catch (Throwable letsHopeWeDontGetHere)
                        {
                        }
                    }
                }
                catch (Throwable t)
                {
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }//while
        }//run
    }

    /**
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public class PollerEvent implements Runnable
    {

        protected NioChannel socket;
        protected int interestOps;
        protected KeyAttachment key;

        public PollerEvent(NioChannel ch, KeyAttachment k, int intOps)
        {
            reset(ch, k, intOps);
        }

        public void reset(NioChannel ch, KeyAttachment k, int intOps)
        {
            socket = ch;
            interestOps = intOps;
            key = k;
        }

        public void reset()
        {
            reset(null, null, 0);
        }

        public void run()
        {
            if (interestOps == OP_REGISTER)
            {
                try
                {
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, key);
                }
                catch (Exception x)
                {
                    log.error("", x);
                }
            } else
            {
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try
                {
                    boolean cancel = false;
                    if (key != null)
                    {
                        final KeyAttachment att = (KeyAttachment) key.attachment();
                        if (att != null)
                        {
                            //handle callback flag
                            if (att.getComet() && (interestOps & OP_CALLBACK) == OP_CALLBACK)
                            {
                                att.setCometNotify(true);
                            } else
                            {
                                att.setCometNotify(false);
                            }
                            interestOps = (interestOps & (~OP_CALLBACK));//remove the callback flag
                            att.access();//to prevent timeout
                            //we are registering the key to start with, reset the fairness counter.
                            int ops = key.interestOps() | interestOps;
                            att.interestOps(ops);
                            key.interestOps(ops);
                            att.setCometOps(ops);
                        } else
                        {
                            cancel = true;
                        }
                    } else
                    {
                        cancel = true;
                    }
                    if (cancel) socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                }
                catch (CancelledKeyException ckx)
                {
                    try
                    {
                        socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT, true);
                    }
                    catch (Exception ignore)
                    {
                    }
                }
            }//end if
        }//run

        public String toString()
        {
            return super.toString() + "[intOps=" + this.interestOps + "]";
        }
    }

    // ------------------------------------------------ Handler Inner Interface

    /**
     * Poller class.
     */
    public class Poller implements Runnable
    {

        protected Selector selector;
        protected ConcurrentLinkedQueue<Runnable> events = new ConcurrentLinkedQueue<Runnable>();

        protected volatile boolean close = false;
        protected long nextExpiration = 0;//optimize expiration handling

        protected AtomicLong wakeupCounter = new AtomicLong(0l);

        protected CountDownLatch stopLatch = new CountDownLatch(1);


        public Poller() throws IOException
        {
            this.selector = Selector.open();
        }

        public Selector getSelector()
        {
            return selector;
        }

        /**
         * Destroy the poller.
         */
        protected void destroy()
        {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            events.clear();
            selector.wakeup();
        }

        public void addEvent(Runnable event)
        {
            events.offer(event);
            if (wakeupCounter.incrementAndGet() == 0) selector.wakeup();
        }

        public void cometInterest(NioChannel socket)
        {
            KeyAttachment att = (KeyAttachment) socket.getAttachment(false);
            add(socket, att.getCometOps());
            if ((att.getCometOps() & OP_CALLBACK) == OP_CALLBACK)
            {
                nextExpiration = 0; //force the check for faster callback
                selector.wakeup();
            }
        }

        public void wakeup()
        {
            selector.wakeup();
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         */
        public void add(final NioChannel socket)
        {
            add(socket, SelectionKey.OP_READ);
        }

        public void add(final NioChannel socket, final int interestOps)
        {
            PollerEvent r = eventCache.poll();
            if (r == null) r = new PollerEvent(socket, null, interestOps);
            else r.reset(socket, null, interestOps);
            addEvent(r);
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         * <code>false</code> if queue was empty
         */
        public boolean events()
        {
            boolean result = false;

            Runnable r = null;
            while ((r = (Runnable) events.poll()) != null)
            {
                result = true;
                try
                {
                    r.run();
                    if (r instanceof PollerEvent)
                    {
                        ((PollerEvent) r).reset();
                        eventCache.offer((PollerEvent) r);
                    }
                }
                catch (Throwable x)
                {
                    log.error("", x);
                }
            }

            return result;
        }

        public void register(final NioChannel socket)
        {
            socket.setPoller(this);
            KeyAttachment key = keyCache.poll();
            final KeyAttachment ka = key != null ? key : new KeyAttachment();
            ka.reset(this, socket, getSocketProperties().getSoTimeout());
            PollerEvent r = eventCache.poll();
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.
            if (r == null) r = new PollerEvent(socket, ka, OP_REGISTER);
            else r.reset(socket, ka, OP_REGISTER);
            addEvent(r);
        }

        public void cancelledKey(SelectionKey key, SocketStatus status, boolean dispatch)
        {
            try
            {
                if (key == null) return;//nothing to do
                KeyAttachment ka = (KeyAttachment) key.attachment();
                if (ka != null && ka.getComet() && status != null)
                {
                    //the comet event takes care of clean up
                    //processSocket(ka.getChannel(), status, dispatch);
                    ka.setComet(false);//to avoid a loop
                    if (status == SocketStatus.TIMEOUT)
                    {
                        processSocket(ka.getChannel(), status, true);
                        return; // don't close on comet timeout
                    } else
                    {
                        processSocket(ka.getChannel(), status, false); //don't dispatch if the lines below are cancelling the key
                    }
                }

                key.attach(null);
                if (ka != null) handler.release(ka.getChannel());
                if (key.isValid()) key.cancel();
                if (key.channel().isOpen()) try
                {
                    key.channel().close();
                }
                catch (Exception ignore)
                {
                }
                try
                {
                    if (ka != null) ka.channel.close(true);
                }
                catch (Exception ignore)
                {
                }
                try
                {
                    if (ka != null && ka.getSendfileData() != null && ka.getSendfileData().fchannel != null && ka.getSendfileData().fchannel.isOpen())
                        ka.getSendfileData().fchannel.close();
                }
                catch (Exception ignore)
                {
                }
                if (ka != null) ka.reset();
            }
            catch (Throwable e)
            {
                if (log.isDebugEnabled()) log.error("", e);
                // Ignore
            }
        }

        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run()
        {
            // Loop until we receive a shutdown command
            while (running)
            {
                try
                {
                    // Loop if endpoint is paused
                    while (paused && (!close))
                    {
                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (InterruptedException e)
                        {
                            // Ignore
                        }
                    }
                    boolean hasEvents = false;

                    hasEvents = (hasEvents | events());
                    // Time to terminate?
                    if (close)
                    {
                        timeout(0, false);
                        break;
                    }
                    int keyCount = 0;
                    try
                    {
                        if (!close)
                        {
                            if (wakeupCounter.getAndSet(-1) > 0)
                            {
                                //if we are here, means we have other stuff to do
                                //do a non blocking select
                                keyCount = selector.selectNow();
                            } else
                            {
                                keyCount = selector.select(selectorTimeout);
                            }
                            wakeupCounter.set(0);
                        }
                        if (close)
                        {
                            timeout(0, false);
                            selector.close();
                            break;
                        }
                    }
                    catch (NullPointerException x)
                    {
                        //sun bug 5076772 on windows JDK 1.5
                        if (log.isDebugEnabled())
                            log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5", x);
                        if (wakeupCounter == null || selector == null) throw x;
                        continue;
                    }
                    catch (CancelledKeyException x)
                    {
                        //sun bug 5076772 on windows JDK 1.5
                        if (log.isDebugEnabled())
                            log.debug("Possibly encountered sun bug 5076772 on windows JDK 1.5", x);
                        if (wakeupCounter == null || selector == null) throw x;
                        continue;
                    }
                    catch (Throwable x)
                    {
                        log.error("", x);
                        continue;
                    }
                    //either we timed out or we woke up, process events first
                    if (keyCount == 0) hasEvents = (hasEvents | events());

                    Iterator iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
                    // Walk through the collection of ready keys and dispatch
                    // any active event.
                    while (iterator != null && iterator.hasNext())
                    {
                        SelectionKey sk = (SelectionKey) iterator.next();
                        KeyAttachment attachment = (KeyAttachment) sk.attachment();
                        // Attachment may be null if another thread has called
                        // cancelledKey()
                        if (attachment == null)
                        {
                            iterator.remove();
                        } else
                        {
                            attachment.access();
                            iterator.remove();
                            processKey(sk, attachment);
                        }
                    }//while

                    //process timeouts
                    timeout(keyCount, hasEvents);
                    if (oomParachute > 0 && oomParachuteData == null) checkParachute();
                }
                catch (OutOfMemoryError oom)
                {
                    try
                    {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }
                    catch (Throwable oomt)
                    {
                        try
                        {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }
                        catch (Throwable letsHopeWeDontGetHere)
                        {
                        }
                    }
                }
            }//while
            synchronized (this)
            {
                this.notifyAll();
            }
            stopLatch.countDown();

        }

        protected boolean processKey(SelectionKey sk, KeyAttachment attachment)
        {
            boolean result = true;
            try
            {
                if (close)
                {
                    cancelledKey(sk, SocketStatus.STOP, false);
                } else if (sk.isValid() && attachment != null)
                {
                    attachment.access();//make sure we don't time out valid sockets
                    sk.attach(attachment);//cant remember why this is here
                    NioChannel channel = attachment.getChannel();
                    if (sk.isReadable() || sk.isWritable())
                    {
                        if (attachment.getSendfileData() != null)
                        {
                            processSendfile(sk, attachment, true, false);
                        } else if (attachment.getComet())
                        {
                            //check if thread is available
                            if (isWorkerAvailable())
                            {
                                //set interest ops to 0 so we don't get multiple
                                //invokations for both read and write on separate threads
                                reg(sk, attachment, 0);
                                //read goes before write
                                if (sk.isReadable())
                                {
                                    //read notification
                                    if (!processSocket(channel, SocketStatus.OPEN))
                                        processSocket(channel, SocketStatus.DISCONNECT);
                                } else
                                {
                                    //future placement of a WRITE notif
                                    if (!processSocket(channel, SocketStatus.OPEN))
                                        processSocket(channel, SocketStatus.DISCONNECT);
                                }
                            } else
                            {
                                result = false;
                            }
                        } else
                        {
                            //later on, improve latch behavior
                            if (isWorkerAvailable())
                            {
                                unreg(sk, attachment, sk.readyOps());
                                boolean close = (!processSocket(channel));
                                if (close)
                                {
                                    cancelledKey(sk, SocketStatus.DISCONNECT, false);
                                }
                            } else
                            {
                                result = false;
                            }
                        }
                    }
                } else
                {
                    //invalid key
                    cancelledKey(sk, SocketStatus.ERROR, false);
                }
            }
            catch (CancelledKeyException ckx)
            {
                cancelledKey(sk, SocketStatus.ERROR, false);
            }
            catch (Throwable t)
            {
                log.error("", t);
            }
            return result;
        }

        public boolean processSendfile(SelectionKey sk, KeyAttachment attachment, boolean reg, boolean event)
        {
            NioChannel sc = null;
            try
            {
                unreg(sk, attachment, sk.readyOps());
                SendfileData sd = attachment.getSendfileData();

                if (log.isTraceEnabled())
                {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                //setup the file channel
                if (sd.fchannel == null)
                {
                    File f = new File(sd.fileName);
                    if (!f.exists())
                    {
                        cancelledKey(sk, SocketStatus.ERROR, false);
                        return false;
                    }
                    sd.fchannel = new FileInputStream(f).getChannel();
                }

                //configure output channel
                sc = attachment.getChannel();
                sc.setSendFile(true);
                //ssl channel is slightly different
                WritableByteChannel wc = (WritableByteChannel) ((sc instanceof SecureNioChannel) ? sc : sc.getIOChannel());

                //we still have data in the buffer
                if (sc.getOutboundRemaining() > 0)
                {
                    if (sc.flushOutbound())
                    {
                        attachment.access();
                    }
                } else
                {
                    long written = sd.fchannel.transferTo(sd.pos, sd.length, wc);
                    if (written > 0)
                    {
                        sd.pos += written;
                        sd.length -= written;
                        attachment.access();
                    } else
                    {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos)
                        {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining() <= 0)
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug("Send file complete for:" + sd.fileName);
                    }
                    attachment.setSendfileData(null);
                    try
                    {
                        sd.fchannel.close();
                    }
                    catch (Exception ignore)
                    {
                    }
                    if (sd.keepAlive)
                    {
                        if (log.isDebugEnabled())
                        {
                            log.debug("Connection is keep alive, registering back for OP_READ");
                        }
                        if (event)
                        {
                            this.add(attachment.getChannel(), SelectionKey.OP_READ);
                        } else
                        {
                            reg(sk, attachment, SelectionKey.OP_READ);
                        }
                    } else
                    {
                        if (log.isDebugEnabled())
                        {
                            log.debug("Send file connection is being closed");
                        }
                        cancelledKey(sk, SocketStatus.STOP, false);
                        return false;
                    }
                } else
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug("OP_WRITE for sendfile:" + sd.fileName);
                    }
                    if (event)
                    {
                        add(attachment.getChannel(), SelectionKey.OP_WRITE);
                    } else
                    {
                        reg(sk, attachment, SelectionKey.OP_WRITE);
                    }
                }
            }
            catch (IOException x)
            {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                cancelledKey(sk, SocketStatus.ERROR, false);
                return false;
            }
            catch (Throwable t)
            {
                log.error("", t);
                cancelledKey(sk, SocketStatus.ERROR, false);
                return false;
            }
            finally
            {
                if (sc != null) sc.setSendFile(false);
            }
            return true;
        }

        protected void unreg(SelectionKey sk, KeyAttachment attachment, int readyOps)
        {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk, attachment, sk.interestOps() & (~readyOps));
        }

        protected void reg(SelectionKey sk, KeyAttachment attachment, int intops)
        {
            sk.interestOps(intops);
            attachment.interestOps(intops);
            //attachment.setCometOps(intops);
        }

        protected void timeout(int keyCount, boolean hasEvents)
        {
            long now = System.currentTimeMillis();
            //don't process timeouts too frequently, but if the selector simply timed out
            //then we can check timeouts to avoid gaps
            if (((keyCount > 0 || hasEvents) || (now < nextExpiration)) && (!close))
            {
                return;
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = now + socketProperties.getTimeoutInterval();
            //timeout
            Set<SelectionKey> keys = selector.keys();
            int keycount = 0;
            for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext(); )
            {
                SelectionKey key = iter.next();
                keycount++;
                try
                {
                    KeyAttachment ka = (KeyAttachment) key.attachment();
                    if (ka == null)
                    {
                        cancelledKey(key, SocketStatus.ERROR, false); //we don't support any keys without attachments
                    } else if (ka.getError())
                    {
                        cancelledKey(key, SocketStatus.ERROR, true);//TODO this is not yet being used
                    } else if (ka.getComet() && ka.getCometNotify())
                    {
                        ka.setCometNotify(false);
                        reg(key, ka, 0);//avoid multiple calls, this gets reregistered after invokation
                        //if (!processSocket(ka.getChannel(), SocketStatus.OPEN_CALLBACK)) processSocket(ka.getChannel(), SocketStatus.DISCONNECT);
                        if (!processSocket(ka.getChannel(), SocketStatus.OPEN))
                            processSocket(ka.getChannel(), SocketStatus.DISCONNECT);
                    } else if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                            (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE)
                    {
                        //only timeout sockets that we are waiting for a read from - or write (send file)
                        long delta = now - ka.getLastAccess();
                        long timeout = (ka.getTimeout() == -1) ? ((long) socketProperties.getSoTimeout()) : (ka.getTimeout());
                        boolean isTimedout = delta > timeout;
                        if (close)
                        {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key, ka);
                        } else if (isTimedout)
                        {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate timeout calls
                            cancelledKey(key, SocketStatus.TIMEOUT, true);
                        } else
                        {
                            long nextTime = now + (timeout - delta);
                            nextExpiration = (nextTime < nextExpiration) ? nextTime : nextExpiration;
                        }
                    }//end if
                }
                catch (CancelledKeyException ckx)
                {
                    cancelledKey(key, SocketStatus.ERROR, false);
                }
            }//for
            if (log.isDebugEnabled())
                log.debug("timeout completed: keys processed=" + keycount + "; now=" + now + "; nextExpiration=" + prevExp + "; " +
                        "keyCount=" + keyCount + "; hasEvents=" + hasEvents + "; eval=" + ((now < prevExp) && (keyCount > 0 || hasEvents) && (!close)));

        }
    }


    // ------------------------------------------------- WorkerStack Inner Class

    /**
     * Server processor class.
     */
    protected class Worker implements Runnable
    {


        protected Thread thread = null;
        protected boolean available = false;
        protected Object socket = null;
        protected SocketStatus status = null;


        /**
         * Process an incoming TCP/IP connection on the specified socket.  Any
         * exception that occurs during processing must be logged and swallowed.
         * <b>NOTE</b>:  This method is called from our Connector's thread.  We
         * must assign it to our own thread so that multiple simultaneous
         * requests can be handled.
         *
         * @param socket TCP socket to process
         */
        protected synchronized void assign(Object socket)
        {

            // Wait for the Processor to get the previous Socket
            while (available)
            {
                try
                {
                    wait();
                }
                catch (InterruptedException e)
                {
                }
            }
            // Store the newly available Socket and notify our thread
            this.socket = socket;
            status = null;
            available = true;
            notifyAll();

        }


        protected synchronized void assign(Object socket, SocketStatus status)
        {

            // Wait for the Processor to get the previous Socket
            while (available)
            {
                try
                {
                    wait();
                }
                catch (InterruptedException e)
                {
                }
            }

            // Store the newly available Socket and notify our thread
            this.socket = socket;
            this.status = status;
            available = true;
            notifyAll();
        }


        /**
         * Await a newly assigned Socket from our Connector, or <code>null</code>
         * if we are supposed to shut down.
         */
        protected synchronized Object await()
        {

            // Wait for the Connector to provide a new Socket
            while (!available)
            {
                try
                {
                    wait();
                }
                catch (InterruptedException e)
                {
                }
            }

            // Notify the Connector that we have received this Socket
            Object socket = this.socket;
            available = false;
            notifyAll();

            return (socket);

        }


        /**
         * The background thread that listens for incoming TCP/IP connections and
         * hands them off to an appropriate processor.
         */
        public void run()
        {

            // Process requests until we receive a shutdown signal
            while (running)
            {
                NioChannel socket = null;
                SelectionKey key = null;
                try
                {
                    // Wait for the next socket to be assigned
                    Object channel = await();
                    if (channel == null)
                        continue;

                    if (channel instanceof SocketChannel)
                    {
                        SocketChannel sc = (SocketChannel) channel;
                        if (!setSocketOptions(sc))
                        {
                            try
                            {
                                sc.socket().close();
                                sc.close();
                            }
                            catch (IOException ix)
                            {
                                if (log.isDebugEnabled()) log.debug("", ix);
                            }
                        } else
                        {
                            //now we have it registered, remove it from the cache

                        }
                    } else
                    {
                        socket = (NioChannel) channel;
                        SocketProcessor sc = processorCache.poll();
                        if (sc == null) sc = new SocketProcessor(socket, status);
                        else sc.reset(socket, status);
                        sc.run();
                    }
                }
                catch (CancelledKeyException cx)
                {
                    if (socket != null && key != null) socket.getPoller().cancelledKey(key, null, false);
                }
                catch (OutOfMemoryError oom)
                {
                    try
                    {
                        oomParachuteData = null;
                        releaseCaches();
                        log.error("", oom);
                    }
                    catch (Throwable oomt)
                    {
                        try
                        {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }
                        catch (Throwable letsHopeWeDontGetHere)
                        {
                        }
                    }
                }
                finally
                {
                    //dereference socket to let GC do its job
                    socket = null;
                    // Finish up this request
                    recycleWorkerThread(this);
                }
            }
        }


        /**
         * Start the background processing thread.
         */
        public void start()
        {
            thread = new Thread(this);
            thread.setName(getName() + "-" + (++curThreads));
            thread.setDaemon(true);
            thread.setPriority(getThreadPriority());
            thread.start();
        }


    }


    // ---------------------------------------------- SocketProcessor Inner Class

    // ------------------------------------------------ Application Buffer Handler
    public class NioBufferHandler implements ApplicationBufferHandler
    {
        protected ByteBuffer readbuf = null;
        protected ByteBuffer writebuf = null;

        public NioBufferHandler(int readsize, int writesize, boolean direct)
        {
            if (direct)
            {
                readbuf = ByteBuffer.allocateDirect(readsize);
                writebuf = ByteBuffer.allocateDirect(writesize);
            } else
            {
                readbuf = ByteBuffer.allocate(readsize);
                writebuf = ByteBuffer.allocate(writesize);
            }
        }

        public ByteBuffer expand(ByteBuffer buffer, int remaining)
        {
            return buffer;
        }

        public ByteBuffer getReadBuffer()
        {
            return readbuf;
        }

        public ByteBuffer getWriteBuffer()
        {
            return writebuf;
        }

    }

    public class WorkerStack
    {

        protected Worker[] workers = null;
        protected int end = 0;

        public WorkerStack(int size)
        {
            workers = new Worker[size];
        }

        /**
         * Put the worker into the queue. If the queue is full (for example if
         * the queue has been reduced in size) the worker will be dropped.
         *
         * @param worker the worker to be appended to the queue (first
         *               element).
         */
        public void push(Worker worker)
        {
            if (end < workers.length)
            {
                workers[end++] = worker;
            } else
            {
                curThreads--;
            }
        }

        /**
         * Get the first object out of the queue. Return null if the queue
         * is empty.
         */
        public Worker pop()
        {
            if (end > 0)
            {
                return workers[--end];
            }
            return null;
        }

        /**
         * Get the first object out of the queue, Return null if the queue
         * is empty.
         */
        public Worker peek()
        {
            return workers[end];
        }

        /**
         * Is the queue empty?
         */
        public boolean isEmpty()
        {
            return (end == 0);
        }

        /**
         * How many elements are there in this queue?
         */
        public int size()
        {
            return (end);
        }

        /**
         * Resize the queue. If there are too many objects in the queue for the
         * new size, drop the excess.
         *
         * @param newSize
         */
        public void resize(int newSize)
        {
            Worker[] newWorkers = new Worker[newSize];
            int len = workers.length;
            if (newSize < len)
            {
                len = newSize;
            }
            System.arraycopy(workers, 0, newWorkers, 0, len);
            workers = newWorkers;
        }
    }

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor implements Runnable
    {

        protected NioChannel socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(NioChannel socket, SocketStatus status)
        {
            reset(socket, status);
        }

        public void reset(NioChannel socket, SocketStatus status)
        {
            this.socket = socket;
            this.status = status;
        }

        public void run()
        {
            synchronized (socket)
            {
                NioEndpoint.this.activeSocketProcessors.addAndGet(1);
                SelectionKey key = null;
                try
                {
                    key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                    int handshake = -1;

                    try
                    {
                        if (key != null) handshake = socket.handshake(key.isReadable(), key.isWritable());
                    }
                    catch (IOException x)
                    {
                        handshake = -1;
                        if (log.isDebugEnabled()) log.debug("Error during SSL handshake", x);
                    }
                    catch (CancelledKeyException ckx)
                    {
                        handshake = -1;
                    }
                    if (handshake == 0)
                    {
                        // Process the request from this socket
                        boolean closed = (status == null) ? (handler.process(socket) == Handler.SocketState.CLOSED) :
                                (handler.event(socket, status) == Handler.SocketState.CLOSED);

                        if (closed)
                        {
                            // Close socket and pool
                            try
                            {
                                KeyAttachment ka = null;
                                if (key != null)
                                {
                                    ka = (KeyAttachment) key.attachment();
                                    if (ka != null) ka.setComet(false);
                                    socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                                }
                                if (socket != null) nioChannels.offer(socket);
                                socket = null;
                                if (ka != null) keyCache.offer(ka);
                                ka = null;
                            }
                            catch (Exception x)
                            {
                                log.error("", x);
                            }
                        }
                    } else if (handshake == -1)
                    {
                        KeyAttachment ka = null;
                        if (key != null)
                        {
                            ka = (KeyAttachment) key.attachment();
                            socket.getPoller().cancelledKey(key, SocketStatus.DISCONNECT, false);
                        }
                        if (socket != null) nioChannels.offer(socket);
                        socket = null;
                        if (ka != null) keyCache.offer(ka);
                        ka = null;
                    } else
                    {
                        final SelectionKey fk = key;
                        final int intops = handshake;
                        final KeyAttachment ka = (KeyAttachment) fk.attachment();
                        ka.getPoller().add(socket, intops);
                    }
                }
                catch (CancelledKeyException cx)
                {
                    socket.getPoller().cancelledKey(key, null, false);
                }
                catch (OutOfMemoryError oom)
                {
                    try
                    {
                        oomParachuteData = null;
                        socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                        releaseCaches();
                        log.error("", oom);
                    }
                    catch (Throwable oomt)
                    {
                        try
                        {
                            System.err.println(oomParachuteMsg);
                            oomt.printStackTrace();
                        }
                        catch (Throwable letsHopeWeDontGetHere)
                        {
                        }
                    }
                }
                catch (Throwable t)
                {
                    log.error("", t);
                    socket.getPoller().cancelledKey(key, SocketStatus.ERROR, false);
                }
                finally
                {
                    socket = null;
                    status = null;
                    //return to cache
                    processorCache.offer(this);
                    NioEndpoint.this.activeSocketProcessors.addAndGet(-1);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class

    // ---------------------------------------------- ThreadFactory Inner Class
    class TaskThreadFactory implements ThreadFactory
    {
        final ThreadGroup group;
        final AtomicInteger threadNumber = new AtomicInteger(1);
        final String namePrefix;

        TaskThreadFactory(String namePrefix)
        {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            this.namePrefix = namePrefix;
        }

        public Thread newThread(Runnable r)
        {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(daemon);
            t.setPriority(getThreadPriority());
            return t;
        }
    }

}
