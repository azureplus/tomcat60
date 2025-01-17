/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.tribes.transport;

import org.apache.catalina.tribes.ChannelMessage;
import org.apache.catalina.tribes.ChannelReceiver;
import org.apache.catalina.tribes.MessageListener;
import org.apache.catalina.tribes.io.ListenCallback;
import org.apache.juli.logging.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>Title: </p>
 * <p/>
 * <p>Description: </p>
 * <p/>
 * <p>Company: </p>
 *
 * @author not attributable
 * @version 1.0
 */
public abstract class ReceiverBase implements ChannelReceiver, ListenCallback, RxTaskPool.TaskCreator
{

    public static final int OPTION_DIRECT_BUFFER = 0x0004;


    protected static org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory.getLog(ReceiverBase.class);

    private MessageListener listener;
    private String host = "auto";
    private InetAddress bind;
    private int port = 4000;
    private int securePort = -1;
    private int rxBufSize = 43800;
    private int txBufSize = 25188;
    private boolean listen = false;
    private RxTaskPool pool;
    private boolean direct = true;
    private long tcpSelectorTimeout = 5000;
    //how many times to search for an available socket
    private int autoBind = 100;
    private int maxThreads = Integer.MAX_VALUE;
    private int minThreads = 6;
    private int maxTasks = 100;
    private int minTasks = 10;
    private boolean tcpNoDelay = true;
    private boolean soKeepAlive = false;
    private boolean ooBInline = true;
    private boolean soReuseAddress = true;
    private boolean soLingerOn = true;
    private int soLingerTime = 3;
    private int soTrafficClass = 0x04 | 0x08 | 0x010;
    private int timeout = 3000; //3 seconds
    private boolean useBufferPool = true;

    private ExecutorService executor;


    public ReceiverBase()
    {
    }

    public void start() throws IOException
    {
        if (executor == null)
        {
            executor = new ThreadPoolExecutor(minThreads, maxThreads, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        }
    }

    public void stop()
    {
        if (executor != null) executor.shutdownNow();//ignore left overs
        executor = null;
    }


    public MessageListener getMessageListener()
    {
        return listener;
    }

    public void setMessageListener(MessageListener listener)
    {
        this.listener = listener;
    }

    public int getPort()
    {
        return port;
    }

    public void setPort(int port)
    {
        this.port = port;
    }

    public int getRxBufSize()
    {
        return rxBufSize;
    }

    public void setRxBufSize(int rxBufSize)
    {
        this.rxBufSize = rxBufSize;
    }

    public int getTxBufSize()
    {
        return txBufSize;
    }

    public void setTxBufSize(int txBufSize)
    {
        this.txBufSize = txBufSize;
    }

    /**
     * @return int
     * @deprecated use getMinThreads()/getMaxThreads()
     */
    public int getTcpThreadCount()
    {
        return getMaxThreads();
    }

    /**
     * @param tcpThreadCount int
     * @deprecated use setMaxThreads/setMinThreads
     */
    public void setTcpThreadCount(int tcpThreadCount)
    {
        setMaxThreads(tcpThreadCount);
        setMinThreads(tcpThreadCount);
    }

    /**
     * @return Returns the bind.
     */
    public InetAddress getBind()
    {
        if (bind == null)
        {
            try
            {
                if ("auto".equals(host))
                {
                    host = java.net.InetAddress.getLocalHost().getHostAddress();
                }
                if (log.isDebugEnabled())
                    log.debug("Starting replication listener on address:" + host);
                bind = java.net.InetAddress.getByName(host);
            }
            catch (IOException ioe)
            {
                log.error("Failed bind replication listener on address:" + host, ioe);
            }
        }
        return bind;
    }

    /**
     * @param bind The bind to set.
     */
    public void setBind(java.net.InetAddress bind)
    {
        this.bind = bind;
    }

    /**
     * recursive bind to find the next available port
     *
     * @param socket    ServerSocket
     * @param portstart int
     * @param retries   int
     * @return int
     * @throws IOException
     */
    protected int bind(ServerSocket socket, int portstart, int retries) throws IOException
    {
        InetSocketAddress addr = null;
        while (retries > 0)
        {
            try
            {
                addr = new InetSocketAddress(getBind(), portstart);
                socket.bind(addr);
                setPort(portstart);
                log.info("Receiver Server Socket bound to:" + addr);
                return 0;
            }
            catch (IOException x)
            {
                retries--;
                if (retries <= 0)
                {
                    log.info("Unable to bind server socket to:" + addr + " throwing error.");
                    throw x;
                }
                portstart++;
                try
                {
                    Thread.sleep(25);
                }
                catch (InterruptedException ti)
                {
                    Thread.currentThread().interrupted();
                }
                retries = bind(socket, portstart, retries);
            }
        }
        return retries;
    }

    public void messageDataReceived(ChannelMessage data)
    {
        if (this.listener != null)
        {
            if (listener.accept(data)) listener.messageReceived(data);
        }
    }

    public int getWorkerThreadOptions()
    {
        int options = 0;
        if (getDirect()) options = options | OPTION_DIRECT_BUFFER;
        return options;
    }

    /**
     * @return int
     * @deprecated use getPort
     */
    public int getTcpListenPort()
    {
        return getPort();
    }

    /**
     * @param tcpListenPort int
     * @deprecated use setPort
     */
    public void setTcpListenPort(int tcpListenPort)
    {
        setPort(tcpListenPort);
    }

    public boolean getDirect()
    {
        return direct;
    }


    public void setDirect(boolean direct)
    {
        this.direct = direct;
    }


    public String getAddress()
    {
        getBind();
        return this.host;
    }

    public void setAddress(String host)
    {
        this.host = host;
    }

    public String getHost()
    {
        return getAddress();
    }

    public void setHost(String host)
    {
        setAddress(host);
    }

    public long getSelectorTimeout()
    {
        return tcpSelectorTimeout;
    }

    public void setSelectorTimeout(long selTimeout)
    {
        tcpSelectorTimeout = selTimeout;
    }

    /**
     * @return long
     * @deprecated use getSelectorTimeout
     */
    public long getTcpSelectorTimeout()
    {
        return getSelectorTimeout();
    }

    /**
     * @param selTimeout long
     * @deprecated use setSelectorTimeout
     */
    public void setTcpSelectorTimeout(long selTimeout)
    {
        setSelectorTimeout(selTimeout);
    }

    public boolean doListen()
    {
        return listen;
    }

    public MessageListener getListener()
    {
        return listener;
    }

    public void setListener(MessageListener listener)
    {
        this.listener = listener;
    }

    public RxTaskPool getTaskPool()
    {
        return pool;
    }

    /**
     * @return String
     * @deprecated use getAddress
     */
    public String getTcpListenAddress()
    {
        return getAddress();
    }

    /**
     * @param tcpListenHost String
     * @deprecated use setAddress
     */
    public void setTcpListenAddress(String tcpListenHost)
    {
        setAddress(tcpListenHost);
    }

    public int getAutoBind()
    {
        return autoBind;
    }

    public void setAutoBind(int autoBind)
    {
        this.autoBind = autoBind;
        if (this.autoBind <= 0) this.autoBind = 1;
    }

    public int getMaxThreads()
    {
        return maxThreads;
    }

    public void setMaxThreads(int maxThreads)
    {
        this.maxThreads = maxThreads;
    }

    public int getMinThreads()
    {
        return minThreads;
    }

    public void setMinThreads(int minThreads)
    {
        this.minThreads = minThreads;
    }

    public boolean getTcpNoDelay()
    {
        return tcpNoDelay;
    }

    public void setTcpNoDelay(boolean tcpNoDelay)
    {
        this.tcpNoDelay = tcpNoDelay;
    }

    public boolean getSoKeepAlive()
    {
        return soKeepAlive;
    }

    public void setSoKeepAlive(boolean soKeepAlive)
    {
        this.soKeepAlive = soKeepAlive;
    }

    public boolean getOoBInline()
    {
        return ooBInline;
    }

    public void setOoBInline(boolean ooBInline)
    {
        this.ooBInline = ooBInline;
    }

    public boolean getSoLingerOn()
    {
        return soLingerOn;
    }

    public void setSoLingerOn(boolean soLingerOn)
    {
        this.soLingerOn = soLingerOn;
    }

    public int getSoLingerTime()
    {
        return soLingerTime;
    }

    public void setSoLingerTime(int soLingerTime)
    {
        this.soLingerTime = soLingerTime;
    }

    public boolean getSoReuseAddress()
    {
        return soReuseAddress;
    }

    public void setSoReuseAddress(boolean soReuseAddress)
    {
        this.soReuseAddress = soReuseAddress;
    }

    public int getSoTrafficClass()
    {
        return soTrafficClass;
    }

    public void setSoTrafficClass(int soTrafficClass)
    {
        this.soTrafficClass = soTrafficClass;
    }

    public int getTimeout()
    {
        return timeout;
    }

    public void setTimeout(int timeout)
    {
        this.timeout = timeout;
    }

    public boolean getUseBufferPool()
    {
        return useBufferPool;
    }

    public void setUseBufferPool(boolean useBufferPool)
    {
        this.useBufferPool = useBufferPool;
    }

    public int getSecurePort()
    {
        return securePort;
    }

    public void setSecurePort(int securePort)
    {
        this.securePort = securePort;
    }

    public int getMinTasks()
    {
        return minTasks;
    }

    public void setMinTasks(int minTasks)
    {
        this.minTasks = minTasks;
    }

    public int getMaxTasks()
    {
        return maxTasks;
    }

    public void setMaxTasks(int maxTasks)
    {
        this.maxTasks = maxTasks;
    }

    public ExecutorService getExecutor()
    {
        return executor;
    }

    public void setExecutor(ExecutorService executor)
    {
        this.executor = executor;
    }

    public boolean isListening()
    {
        return listen;
    }

    public void setListen(boolean doListen)
    {
        this.listen = doListen;
    }

    public void setLog(Log log)
    {
        this.log = log;
    }

    public void setPool(RxTaskPool pool)
    {
        this.pool = pool;
    }

    public void heartbeat()
    {
        //empty operation
    }

}