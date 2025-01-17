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
import org.apache.catalina.connector.Connector;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.ArrayList;


/**
 * Standard implementation of the <code>Service</code> interface.  The
 * associated Container is generally an instance of Engine, but this is
 * not required.
 *
 * @author Craig R. McClanahan
 */

public class StandardService
        implements Lifecycle, Service, MBeanRegistration
{
    /**
     * Descriptive information about this component implementation.
     */
    private static final String info =
            "org.apache.catalina.core.StandardService/1.0";


    // ----------------------------------------------------- Instance Variables
    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
            StringManager.getManager(Constants.Package);
    private static Log log = LogFactory.getLog(StandardService.class);
    /**
     * The property change support for this component.
     */
    protected PropertyChangeSupport support = new PropertyChangeSupport(this);
    /**
     * The set of Connectors associated with this Service.
     */
    protected Connector connectors[] = new Connector[0];
    /**
     *
     */
    protected ArrayList<Executor> executors = new ArrayList<Executor>();
    /**
     * The Container associated with this Service. (In the case of the
     * org.apache.catalina.startup.Embedded subclass, this holds the most
     * recently added Engine.)
     */
    protected Container container = null;
    /**
     * Has this component been initialized?
     */
    protected boolean initialized = false;
    protected String type;
    protected String domain;
    protected String suffix;
    protected ObjectName oname;


    // ------------------------------------------------------------- Properties
    protected ObjectName controller;
    protected MBeanServer mserver;
    /**
     * The name of this service.
     */
    private String name = null;
    /**
     * The lifecycle event support for this component.
     */
    private LifecycleSupport lifecycle = new LifecycleSupport(this);
    /**
     * The <code>Server</code> that owns this Service, if any.
     */
    private Server server = null;
    /**
     * Has this component been started?
     */
    private boolean started = false;

    /**
     * Return the <code>Container</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     */
    public Container getContainer()
    {

        return (this.container);

    }

    /**
     * Set the <code>Container</code> that handles requests for all
     * <code>Connectors</code> associated with this Service.
     *
     * @param container The new Container
     */
    public void setContainer(Container container)
    {

        Container oldContainer = this.container;
        if ((oldContainer != null) && (oldContainer instanceof Engine))
            ((Engine) oldContainer).setService(null);
        this.container = container;
        if ((this.container != null) && (this.container instanceof Engine))
            ((Engine) this.container).setService(this);
        if (started && (this.container != null) &&
                (this.container instanceof Lifecycle))
        {
            try
            {
                ((Lifecycle) this.container).start();
            }
            catch (LifecycleException e)
            {
                ;
            }
        }
        synchronized (connectors)
        {
            for (int i = 0; i < connectors.length; i++)
                connectors[i].setContainer(this.container);
        }
        if (started && (oldContainer != null) &&
                (oldContainer instanceof Lifecycle))
        {
            try
            {
                ((Lifecycle) oldContainer).stop();
            }
            catch (LifecycleException e)
            {
                ;
            }
        }

        // Report this property change to interested listeners
        support.firePropertyChange("container", oldContainer, this.container);

    }


    // --------------------------------------------------------- Public Methods

    public ObjectName getContainerName()
    {
        if (container instanceof ContainerBase)
        {
            return ((ContainerBase) container).getJmxName();
        }
        return null;
    }

    /**
     * Return descriptive information about this Service implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo()
    {

        return (info);

    }

    /**
     * Return the name of this Service.
     */
    public String getName()
    {

        return (this.name);

    }

    /**
     * Set the name of this Service.
     *
     * @param name The new service name
     */
    public void setName(String name)
    {

        this.name = name;

    }

    /**
     * Return the <code>Server</code> with which we are associated (if any).
     */
    public Server getServer()
    {

        return (this.server);

    }

    /**
     * Set the <code>Server</code> with which we are associated (if any).
     *
     * @param server The server that owns this Service
     */
    public void setServer(Server server)
    {

        this.server = server;

    }

    /**
     * Add a new Connector to the set of defined Connectors, and associate it
     * with this Service's Container.
     *
     * @param connector The Connector to be added
     */
    public void addConnector(Connector connector)
    {

        synchronized (connectors)
        {
            connector.setContainer(this.container);
            connector.setService(this);
            Connector results[] = new Connector[connectors.length + 1];
            System.arraycopy(connectors, 0, results, 0, connectors.length);
            results[connectors.length] = connector;
            connectors = results;

            if (initialized)
            {
                try
                {
                    connector.initialize();
                }
                catch (LifecycleException e)
                {
                    log.error(sm.getString(
                            "standardService.connector.initFailed",
                            connector), e);
                }
            }

            if (started && (connector instanceof Lifecycle))
            {
                try
                {
                    ((Lifecycle) connector).start();
                }
                catch (LifecycleException e)
                {
                    log.error(sm.getString(
                            "standardService.connector.startFailed",
                            connector), e);
                }
            }

            // Report this property change to interested listeners
            support.firePropertyChange("connector", null, connector);
        }

    }


    // ------------------------------------------------------ Lifecycle Methods

    public ObjectName[] getConnectorNames()
    {
        ObjectName results[] = new ObjectName[connectors.length];
        for (int i = 0; i < results.length; i++)
        {
            results[i] = connectors[i].getObjectName();
        }
        return results;
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

    /**
     * Find and return the set of Connectors associated with this Service.
     */
    public Connector[] findConnectors()
    {

        return (connectors);

    }

    /**
     * Remove the specified Connector from the set associated from this
     * Service.  The removed Connector will also be disassociated from our
     * Container.
     *
     * @param connector The Connector to be removed
     */
    public void removeConnector(Connector connector)
    {

        synchronized (connectors)
        {
            int j = -1;
            for (int i = 0; i < connectors.length; i++)
            {
                if (connector == connectors[i])
                {
                    j = i;
                    break;
                }
            }
            if (j < 0)
                return;
            if (started && (connectors[j] instanceof Lifecycle))
            {
                try
                {
                    ((Lifecycle) connectors[j]).stop();
                }
                catch (LifecycleException e)
                {
                    log.error(sm.getString(
                            "standardService.connector.stopFailed",
                            connectors[j]), e);
                }
            }
            connectors[j].setContainer(null);
            connector.setService(null);
            int k = 0;
            Connector results[] = new Connector[connectors.length - 1];
            for (int i = 0; i < connectors.length; i++)
            {
                if (i != j)
                    results[k++] = connectors[i];
            }
            connectors = results;

            // Report this property change to interested listeners
            support.firePropertyChange("connector", connector, null);
        }

    }

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

        StringBuffer sb = new StringBuffer("StandardService[");
        sb.append(getName());
        sb.append("]");
        return (sb.toString());

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
     * Adds a named executor to the service
     *
     * @param ex Executor
     */
    public void addExecutor(Executor ex)
    {
        synchronized (executors)
        {
            if (!executors.contains(ex))
            {
                executors.add(ex);
                if (started)
                    try
                    {
                        ex.start();
                    }
                    catch (LifecycleException x)
                    {
                        log.error("Executor.start", x);
                    }
            }
        }
    }

    /**
     * Retrieves all executors
     *
     * @return Executor[]
     */
    public Executor[] findExecutors()
    {
        synchronized (executors)
        {
            Executor[] arr = new Executor[executors.size()];
            executors.toArray(arr);
            return arr;
        }
    }

    /**
     * Retrieves executor by name, null if not found
     *
     * @param name String
     * @return Executor
     */
    public Executor getExecutor(String name)
    {
        synchronized (executors)
        {
            for (int i = 0; i < executors.size(); i++)
            {
                if (name.equals(executors.get(i).getName()))
                    return executors.get(i);
            }
        }
        return null;
    }

    /**
     * Removes an executor from the service
     *
     * @param ex Executor
     */
    public void removeExecutor(Executor ex)
    {
        synchronized (executors)
        {
            if (executors.remove(ex) && started)
            {
                try
                {
                    ex.stop();
                }
                catch (LifecycleException e)
                {
                    log.error("Executor.stop", e);
                }
            }
        }
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
            if (log.isInfoEnabled())
            {
                log.info(sm.getString("standardService.start.started"));
            }
            return;
        }

        if (!initialized)
            init();

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_START_EVENT, null);
        if (log.isInfoEnabled())
            log.info(sm.getString("standardService.start.name", this.name));
        lifecycle.fireLifecycleEvent(START_EVENT, null);
        started = true;

        // Start our defined Container first
        if (container != null)
        {
            synchronized (container)
            {
                if (container instanceof Lifecycle)
                {
                    ((Lifecycle) container).start();
                }
            }
        }

        synchronized (executors)
        {
            for (int i = 0; i < executors.size(); i++)
            {
                executors.get(i).start();
            }
        }

        // Start our defined Connectors second
        synchronized (connectors)
        {
            for (int i = 0; i < connectors.length; i++)
            {
                try
                {
                    ((Lifecycle) connectors[i]).start();
                }
                catch (Exception e)
                {
                    log.error(sm.getString(
                            "standardService.connector.startFailed",
                            connectors[i]), e);
                }
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
        {
            return;
        }

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_STOP_EVENT, null);

        // Stop our defined Connectors first
        synchronized (connectors)
        {
            for (int i = 0; i < connectors.length; i++)
            {
                try
                {
                    connectors[i].pause();
                }
                catch (Exception e)
                {
                    log.error(sm.getString(
                            "standardService.connector.pauseFailed",
                            connectors[i]), e);
                }
            }
        }

        // Heuristic: Sleep for a while to ensure pause of the connector
        try
        {
            Thread.sleep(1000);
        }
        catch (InterruptedException e)
        {
            // Ignore
        }

        lifecycle.fireLifecycleEvent(STOP_EVENT, null);
        if (log.isInfoEnabled())
            log.info
                    (sm.getString("standardService.stop.name", this.name));
        started = false;

        // Stop our defined Container second
        if (container != null)
        {
            synchronized (container)
            {
                if (container instanceof Lifecycle)
                {
                    ((Lifecycle) container).stop();
                }
            }
        }
        // FIXME pero -- Why container stop first? KeepAlive connetions can send request! 
        // Stop our defined Connectors first
        synchronized (connectors)
        {
            for (int i = 0; i < connectors.length; i++)
            {
                try
                {
                    ((Lifecycle) connectors[i]).stop();
                }
                catch (Exception e)
                {
                    log.error(sm.getString(
                            "standardService.connector.stopFailed",
                            connectors[i]), e);
                }
            }
        }

        synchronized (executors)
        {
            for (int i = 0; i < executors.size(); i++)
            {
                executors.get(i).stop();
            }
        }

        if (oname == controller)
        {
            // we registered ourself on init().
            // That should be the typical case - this object is just for
            // backward compat, nobody should bother to load it explicitely
            Registry.getRegistry(null, null).unregisterComponent(oname);
            Executor[] executors = findExecutors();
            for (int i = 0; i < executors.length; i++)
            {
                try
                {
                    ObjectName executorObjectName =
                            new ObjectName(domain + ":type=Executor,name=" + executors[i].getName());
                    Registry.getRegistry(null, null).unregisterComponent(executorObjectName);
                }
                catch (Exception e)
                {
                    // Ignore (invalid ON, which cannot happen)
                }
            }
        }


        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(AFTER_STOP_EVENT, null);

    }

    /**
     * Invoke a pre-startup initialization. This is used to allow connectors
     * to bind to restricted ports under Unix operating environments.
     */
    public void initialize()
            throws LifecycleException
    {
        // Service shouldn't be used with embeded, so it doesn't matter
        if (initialized)
        {
            if (log.isInfoEnabled())
                log.info(sm.getString("standardService.initialize.initialized"));
            return;
        }
        initialized = true;

        if (oname == null)
        {
            try
            {
                // Hack - Server should be deprecated...
                Container engine = this.getContainer();
                domain = engine.getName();
                oname = new ObjectName(domain + ":type=Service,serviceName=" + name);
                this.controller = oname;
                Registry.getRegistry(null, null)
                        .registerComponent(this, oname, null);

                Executor[] executors = findExecutors();
                for (int i = 0; i < executors.length; i++)
                {
                    ObjectName executorObjectName =
                            new ObjectName(domain + ":type=Executor,name=" + executors[i].getName());
                    Registry.getRegistry(null, null)
                            .registerComponent(executors[i], executorObjectName, null);
                }

            }
            catch (Exception e)
            {
                log.error(sm.getString("standardService.register.failed", domain), e);
            }


        }
        if (server == null)
        {
            // Register with the server 
            // HACK: ServerFactory should be removed...

            ServerFactory.getServer().addService(this);
        }


        // Initialize our defined Connectors
        synchronized (connectors)
        {
            for (int i = 0; i < connectors.length; i++)
            {
                try
                {
                    connectors[i].initialize();
                }
                catch (Exception e)
                {
                    String message = sm.getString(
                            "standardService.connector.initFailed",
                            connectors[i]);
                    log.error(message, e);

                    if (Boolean.getBoolean("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE"))
                        throw new LifecycleException(message);
                }
            }
        }
    }

    public void destroy() throws LifecycleException
    {
        if (started) stop();
        // FIXME unregister should be here probably -- stop doing that ?
    }

    public void init()
    {
        try
        {
            initialize();
        }
        catch (Throwable t)
        {
            log.error(sm.getString("standardService.initialize.failed", domain), t);
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
