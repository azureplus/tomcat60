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
package org.apache.catalina.core;

import org.apache.catalina.*;
import org.apache.catalina.deploy.NamingResources;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.StringCache;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.AccessControlException;
import java.util.Random;


/**
 * Standard implementation of the <b>Server</b> interface, available for use
 * (but not required) when deploying and starting Catalina.
 *
 * @author Craig R. McClanahan
 */
public final class StandardServer
        implements Lifecycle, Server, MBeanRegistration
{
    /**
     * Descriptive information about this Server implementation.
     */
    private static final String info =
            "org.apache.catalina.core.StandardServer/1.0";


    // -------------------------------------------------------------- Constants
    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
            StringManager.getManager(Constants.Package);


    // ------------------------------------------------------------ Constructor
    private static Log log = LogFactory.getLog(StandardServer.class);


    // ----------------------------------------------------- Instance Variables
    /**
     * ServerLifecycleListener classname.
     */
    private static String SERVER_LISTENER_CLASS_NAME =
            "org.apache.catalina.mbeans.ServerLifecycleListener";
    /**
     * The property change support for this component.
     */
    protected PropertyChangeSupport support = new PropertyChangeSupport(this);
    protected String type;
    protected String domain;
    protected String suffix;
    protected ObjectName oname;
    protected MBeanServer mserver;
    /**
     * Global naming resources context.
     */
    private javax.naming.Context globalNamingContext = null;
    /**
     * Global naming resources.
     */
    private NamingResources globalNamingResources = null;
    /**
     * The lifecycle event support for this component.
     */
    private LifecycleSupport lifecycle = new LifecycleSupport(this);
    /**
     * The naming context listener for this web application.
     */
    private NamingContextListener namingContextListener = null;
    /**
     * The port number on which we wait for shutdown commands.
     */
    private int port = 8005;
    /**
     * A random number generator that is <strong>only</strong> used if
     * the shutdown command string is longer than 1024 characters.
     */
    private Random random = null;
    /**
     * The set of Services associated with this Server.
     */
    private Service services[] = new Service[0];
    /**
     * The shutdown command string we are looking for.
     */
    private String shutdown = "SHUTDOWN";
    /**
     * Has this component been started?
     */
    private boolean started = false;

    // ------------------------------------------------------------- Properties
    /**
     * Has this component been initialized?
     */
    private boolean initialized = false;
    private volatile boolean stopAwait = false;
    /**
     * Thread that currently is inside our await() method.
     */
    private volatile Thread awaitThread = null;
    /**
     * Server socket that is used to wait for the shutdown command.
     */
    private volatile ServerSocket awaitSocket = null;


    /**
     * Construct a default instance of this class.
     */
    public StandardServer()
    {

        super();
        ServerFactory.setServer(this);

        globalNamingResources = new NamingResources();
        globalNamingResources.setContainer(this);

        if (isUseNaming())
        {
            if (namingContextListener == null)
            {
                namingContextListener = new NamingContextListener();
                addLifecycleListener(namingContextListener);
            }
        }

    }

    /**
     * Return the global naming resources context.
     */
    public javax.naming.Context getGlobalNamingContext()
    {

        return (this.globalNamingContext);

    }

    /**
     * Set the global naming resources context.
     *
     * @param globalNamingContext The new global naming resource context
     */
    public void setGlobalNamingContext
    (javax.naming.Context globalNamingContext)
    {

        this.globalNamingContext = globalNamingContext;

    }

    /**
     * Return the global naming resources.
     */
    public NamingResources getGlobalNamingResources()
    {

        return (this.globalNamingResources);

    }

    /**
     * Set the global naming resources.
     *
     * @param globalNamingResources The new global naming resources
     */
    public void setGlobalNamingResources
    (NamingResources globalNamingResources)
    {

        NamingResources oldGlobalNamingResources =
                this.globalNamingResources;
        this.globalNamingResources = globalNamingResources;
        this.globalNamingResources.setContainer(this);
        support.firePropertyChange("globalNamingResources",
                oldGlobalNamingResources,
                this.globalNamingResources);

    }

    /**
     * Return descriptive information about this Server implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo()
    {

        return (info);

    }


    // --------------------------------------------------------- Server Methods

    /**
     * Report the current Tomcat Server Release number
     *
     * @return Tomcat release identifier
     */
    public String getServerInfo()
    {

        return ServerInfo.getServerInfo();
    }

    /**
     * Return the port number we listen to for shutdown commands.
     */
    public int getPort()
    {

        return (this.port);

    }

    /**
     * Set the port number we listen to for shutdown commands.
     *
     * @param port The new port number
     */
    public void setPort(int port)
    {

        this.port = port;

    }

    /**
     * Return the shutdown command string we are waiting for.
     */
    public String getShutdown()
    {

        return (this.shutdown);

    }

    /**
     * Set the shutdown command we are waiting for.
     *
     * @param shutdown The new shutdown command
     */
    public void setShutdown(String shutdown)
    {

        this.shutdown = shutdown;

    }

    /**
     * Add a new Service to the set of defined Services.
     *
     * @param service The Service to be added
     */
    public void addService(Service service)
    {

        service.setServer(this);

        synchronized (services)
        {
            Service results[] = new Service[services.length + 1];
            System.arraycopy(services, 0, results, 0, services.length);
            results[services.length] = service;
            services = results;

            if (initialized)
            {
                try
                {
                    service.initialize();
                }
                catch (LifecycleException e)
                {
                    log.error(e);
                }
            }

            if (started && (service instanceof Lifecycle))
            {
                try
                {
                    ((Lifecycle) service).start();
                }
                catch (LifecycleException e)
                {
                    ;
                }
            }

            // Report this property change to interested listeners
            support.firePropertyChange("service", null, service);
        }

    }

    public void stopAwait()
    {
        stopAwait = true;
        Thread t = awaitThread;
        if (t != null)
        {
            ServerSocket s = awaitSocket;
            if (s != null)
            {
                awaitSocket = null;
                try
                {
                    s.close();
                }
                catch (IOException e)
                {
                    // Ignored
                }
            }
            t.interrupt();
            try
            {
                t.join(1000);
            }
            catch (InterruptedException e)
            {
                // Ignored
            }
        }
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Wait until a proper shutdown command is received, then return.
     * This keeps the main thread alive - the thread pool listening for http
     * connections is daemon threads.
     */
    public void await()
    {
        // Negative values - don't wait on port - tomcat is embedded or we just don't like ports
        if (port == -2)
        {
            // undocumented yet - for embedding apps that are around, alive.
            return;
        }
        if (port == -1)
        {
            try
            {
                awaitThread = Thread.currentThread();
                while (!stopAwait)
                {
                    try
                    {
                        Thread.sleep(10000);
                    }
                    catch (InterruptedException ex)
                    {
                        // continue and check the flag
                    }
                }
            }
            finally
            {
                awaitThread = null;
            }
            return;
        }

        // Set up a server socket to wait on
        try
        {
            awaitSocket =
                    new ServerSocket(port, 1,
                            InetAddress.getByName("localhost"));
        }
        catch (IOException e)
        {
            log.error("StandardServer.await: create[" + port
                    + "]: ", e);
            return;
        }

        try
        {
            awaitThread = Thread.currentThread();

            // Loop waiting for a connection and a valid command
            while (!stopAwait)
            {
                ServerSocket serverSocket = awaitSocket;
                if (serverSocket == null)
                {
                    break;
                }

                // Wait for the next connection
                Socket socket = null;
                StringBuilder command = new StringBuilder();
                try
                {
                    InputStream stream = null;
                    long acceptStartTime = System.currentTimeMillis();
                    try
                    {
                        socket = serverSocket.accept();
                        socket.setSoTimeout(10 * 1000);  // Ten seconds
                        stream = socket.getInputStream();
                    }
                    catch (SocketTimeoutException ste)
                    {
                        // This should never happen but bug 56684 suggests that
                        // it does.
                        log.warn(sm.getString("standardServer.accept.timeout",
                                Long.valueOf(System.currentTimeMillis() - acceptStartTime)), ste);
                        continue;
                    }
                    catch (AccessControlException ace)
                    {
                        log.warn("StandardServer.accept security exception: "
                                + ace.getMessage(), ace);
                        continue;
                    }
                    catch (IOException e)
                    {
                        if (stopAwait)
                        {
                            // Wait was aborted with socket.close()
                            break;
                        }
                        log.error("StandardServer.await: accept: ", e);
                        break;
                    }

                    // Read a set of characters from the socket
                    int expected = 1024; // Cut off to avoid DoS attack
                    while (expected < shutdown.length())
                    {
                        if (random == null)
                            random = new Random();
                        expected += (random.nextInt() % 1024);
                    }
                    while (expected > 0)
                    {
                        int ch = -1;
                        try
                        {
                            ch = stream.read();
                        }
                        catch (IOException e)
                        {
                            log.warn("StandardServer.await: read: ", e);
                            ch = -1;
                        }
                        if (ch < 32)  // Control character or EOF terminates loop
                            break;
                        command.append((char) ch);
                        expected--;
                    }
                }
                finally
                {
                    // Close the socket now that we are done with it
                    try
                    {
                        if (socket != null)
                        {
                            socket.close();
                        }
                    }
                    catch (IOException e)
                    {
                        // Ignore
                    }
                }

                // Match against our command string
                boolean match = command.toString().equals(shutdown);
                if (match)
                {
                    break;
                } else
                    log.warn("StandardServer.await: Invalid command '" +
                            command.toString() + "' received");
            }
        }
        finally
        {
            ServerSocket serverSocket = awaitSocket;
            awaitThread = null;
            awaitSocket = null;

            // Close the server socket and return
            if (serverSocket != null)
            {
                try
                {
                    serverSocket.close();
                }
                catch (IOException e)
                {
                    // Ignore
                }
            }
        }
    }

    /**
     * Return the specified Service (if it exists); otherwise return
     * <code>null</code>.
     *
     * @param name Name of the Service to be returned
     */
    public Service findService(String name)
    {

        if (name == null)
        {
            return (null);
        }
        synchronized (services)
        {
            for (int i = 0; i < services.length; i++)
            {
                if (name.equals(services[i].getName()))
                {
                    return (services[i]);
                }
            }
        }
        return (null);

    }

    /**
     * Return the set of Services defined within this Server.
     */
    public Service[] findServices()
    {

        return (services);

    }

    /**
     * Return the JMX service names.
     */
    public ObjectName[] getServiceNames()
    {
        ObjectName onames[] = new ObjectName[services.length];
        for (int i = 0; i < services.length; i++)
        {
            onames[i] = ((StandardService) services[i]).getObjectName();
        }
        return onames;
    }

    /**
     * Remove the specified Service from the set associated from this
     * Server.
     *
     * @param service The Service to be removed
     */
    public void removeService(Service service)
    {

        synchronized (services)
        {
            int j = -1;
            for (int i = 0; i < services.length; i++)
            {
                if (service == services[i])
                {
                    j = i;
                    break;
                }
            }
            if (j < 0)
                return;
            if (services[j] instanceof Lifecycle)
            {
                try
                {
                    ((Lifecycle) services[j]).stop();
                }
                catch (LifecycleException e)
                {
                    ;
                }
            }
            int k = 0;
            Service results[] = new Service[services.length - 1];
            for (int i = 0; i < services.length; i++)
            {
                if (i != j)
                    results[k++] = services[i];
            }
            services = results;

            // Report this property change to interested listeners
            support.firePropertyChange("service", service, null);
        }

    }

    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {

        support.addPropertyChangeListener(listener);

    }


    // ------------------------------------------------------ Lifecycle Methods

    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {

        support.removePropertyChangeListener(listener);

    }

    /**
     * Return a String representation of this component.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("StandardServer[");
        sb.append(getPort());
        sb.append("]");
        return (sb.toString());

    }

    /**
     * Write the configuration information for this entire <code>Server</code>
     * out to the server.xml configuration file.
     *
     * @throws javax.management.InstanceNotFoundException  if the managed resource object cannot be found
     * @throws javax.management.MBeanException             if the initializer of the object throws an exception, or
     *                                                     persistence is not supported
     * @throws javax.management.RuntimeOperationsException if an exception is reported by the persistence mechanism
     */
    public synchronized void storeConfig() throws Exception
    {
        ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
        mserver.invoke(sname, "storeConfig", null, null);
    }

    /**
     * Write the configuration information for <code>Context</code>
     * out to the specified configuration file.
     *
     * @throws javax.management.InstanceNotFoundException  if the managed resource object
     *                                                     cannot be found
     * @throws javax.management.MBeanException             if the initializer of the object throws
     *                                                     an exception, or persistence is not supported
     * @throws javax.management.RuntimeOperationsException if an exception is reported
     *                                                     by the persistence mechanism
     */
    public synchronized void storeContext(Context context) throws Exception
    {

        ObjectName sname = null;
        try
        {
            sname = new ObjectName("Catalina:type=StoreConfig");
            if (mserver.isRegistered(sname))
            {
                mserver.invoke(sname, "store",
                        new Object[]{context},
                        new String[]{"java.lang.String"});
            } else
                log.error("StoreConfig mbean not registered" + sname);
        }
        catch (Throwable t)
        {
            log.error(t);
        }

    }

    /**
     * Return true if naming should be used.
     */
    private boolean isUseNaming()
    {
        boolean useNaming = true;
        // Reading the "catalina.useNaming" environment variable
        String useNamingProperty = System.getProperty("catalina.useNaming");
        if ((useNamingProperty != null)
                && (useNamingProperty.equals("false")))
        {
            useNaming = false;
        }
        return useNaming;
    }

    /**
     * Add a LifecycleEvent listener to this component.
     *
     * @param listener The listener to add
     */
    public void addLifecycleListener(LifecycleListener listener)
    {

        lifecycle.addLifecycleListener(listener);

    }

    /**
     * Get the lifecycle listeners associated with this lifecycle. If this
     * Lifecycle has no listeners registered, a zero-length array is returned.
     */
    public LifecycleListener[] findLifecycleListeners()
    {

        return lifecycle.findLifecycleListeners();

    }

    /**
     * Remove a LifecycleEvent listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removeLifecycleListener(LifecycleListener listener)
    {

        lifecycle.removeLifecycleListener(listener);

    }

    /**
     * Prepare for the beginning of active use of the public methods of this
     * component.  This method should be called before any of the public
     * methods of this component are utilized.  It should also send a
     * LifecycleEvent of type START_EVENT to any registered listeners.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    public void start() throws LifecycleException
    {

        // Validate and update our current component state
        if (started)
        {
            log.debug(sm.getString("standardServer.start.started"));
            return;
        }

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_START_EVENT, null);

        lifecycle.fireLifecycleEvent(START_EVENT, null);
        started = true;

        // Start our defined Services
        synchronized (services)
        {
            for (int i = 0; i < services.length; i++)
            {
                if (services[i] instanceof Lifecycle)
                    ((Lifecycle) services[i]).start();
            }
        }

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(AFTER_START_EVENT, null);

    }

    /**
     * Gracefully terminate the active use of the public methods of this
     * component.  This method should be the last one called on a given
     * instance of this component.  It should also send a LifecycleEvent
     * of type STOP_EVENT to any registered listeners.
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that needs to be reported
     */
    public void stop() throws LifecycleException
    {

        // Validate and update our current component state
        if (!started)
            return;

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_STOP_EVENT, null);

        lifecycle.fireLifecycleEvent(STOP_EVENT, null);
        started = false;

        // Stop our defined Services
        for (int i = 0; i < services.length; i++)
        {
            if (services[i] instanceof Lifecycle)
                ((Lifecycle) services[i]).stop();
        }

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(AFTER_STOP_EVENT, null);

        stopAwait();

    }

    public void init() throws Exception
    {
        initialize();
    }

    /**
     * Invoke a pre-startup initialization. This is used to allow connectors
     * to bind to restricted ports under Unix operating environments.
     */
    public void initialize()
            throws LifecycleException
    {
        if (initialized)
        {
            log.info(sm.getString("standardServer.initialize.initialized"));
            return;
        }
        lifecycle.fireLifecycleEvent(INIT_EVENT, null);
        initialized = true;

        if (oname == null)
        {
            try
            {
                oname = new ObjectName("Catalina:type=Server");
                Registry.getRegistry(null, null)
                        .registerComponent(this, oname, null);
            }
            catch (Exception e)
            {
                log.error("Error registering ", e);
            }
        }

        // Register global String cache
        try
        {
            ObjectName oname2 =
                    new ObjectName(oname.getDomain() + ":type=StringCache");
            Registry.getRegistry(null, null)
                    .registerComponent(new StringCache(), oname2, null);
        }
        catch (Exception e)
        {
            log.error("Error registering ", e);
        }

        // Initialize our defined Services
        for (int i = 0; i < services.length; i++)
        {
            services[i].initialize();
        }
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

}
