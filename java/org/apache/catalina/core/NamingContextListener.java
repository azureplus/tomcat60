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
import org.apache.catalina.deploy.*;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.naming.*;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;


/**
 * Helper class used to initialize and populate the JNDI context associated
 * with each context and server.
 *
 * @author Remy Maucherat
 */

public class NamingContextListener
        implements LifecycleListener, ContainerListener, PropertyChangeListener
{

    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);


    // ----------------------------------------------------- Instance Variables
    private static Log log = LogFactory.getLog(NamingContextListener.class);
    protected Log logger = log;
    /**
     * Name of the associated naming context.
     */
    protected String name = "/";
    /**
     * Associated container.
     */
    protected Object container = null;
    /**
     * Initialized flag.
     */
    protected boolean initialized = false;
    /**
     * Associated naming resources.
     */
    protected NamingResources namingResources = null;
    /**
     * Associated JNDI context.
     */
    protected NamingContext namingContext = null;
    /**
     * Comp context.
     */
    protected javax.naming.Context compCtx = null;
    /**
     * Env context.
     */
    protected javax.naming.Context envCtx = null;
    /**
     * Objectnames hashtable.
     */
    protected HashMap objectNames = new HashMap();


    // ------------------------------------------------------------- Properties

    /**
     * Return the "name" property.
     */
    public String getName()
    {
        return (this.name);
    }


    /**
     * Set the "name" property.
     *
     * @param name The new name
     */
    public void setName(String name)
    {
        this.name = name;
    }


    /**
     * Return the comp context.
     */
    public javax.naming.Context getCompContext()
    {
        return this.compCtx;
    }


    /**
     * Return the env context.
     */
    public javax.naming.Context getEnvContext()
    {
        return this.envCtx;
    }


    /**
     * Return the associated naming context.
     */
    public NamingContext getNamingContext()
    {
        return (this.namingContext);
    }


    // ---------------------------------------------- LifecycleListener Methods


    /**
     * Acknowledge the occurrence of the specified event.
     *
     * @param event LifecycleEvent that has occurred
     */
    public void lifecycleEvent(LifecycleEvent event)
    {

        container = event.getLifecycle();

        if (container instanceof Context)
        {
            namingResources = ((Context) container).getNamingResources();
            logger = log;
        } else if (container instanceof Server)
        {
            namingResources = ((Server) container).getGlobalNamingResources();
        } else
        {
            return;
        }

        if (event.getType() == Lifecycle.START_EVENT)
        {

            if (initialized)
                return;

            Hashtable contextEnv = new Hashtable();
            try
            {
                namingContext = new NamingContext(contextEnv, getName());
            }
            catch (NamingException e)
            {
                // Never happens
            }
            ContextAccessController.setSecurityToken(getName(), container);
            ContextAccessController.setSecurityToken(container, container);
            ContextBindings.bindContext(container, namingContext, container);
            if (log.isDebugEnabled())
            {
                log.debug("Bound " + container);
            }

            // Setting the context in read/write mode
            ContextAccessController.setWritable(getName(), container);

            try
            {
                createNamingContext();
            }
            catch (NamingException e)
            {
                logger.error
                        (sm.getString("naming.namingContextCreationFailed", e));
            }

            namingResources.addPropertyChangeListener(this);

            // Binding the naming context to the class loader
            if (container instanceof Context)
            {
                // Setting the context in read only mode
                ContextAccessController.setReadOnly(getName());
                try
                {
                    ContextBindings.bindClassLoader
                            (container, container,
                                    ((Container) container).getLoader().getClassLoader());
                }
                catch (NamingException e)
                {
                    logger.error(sm.getString("naming.bindFailed", e));
                }
            }

            if (container instanceof Server)
            {
                org.apache.naming.factory.ResourceLinkFactory.setGlobalContext
                        (namingContext);
                try
                {
                    ContextBindings.bindClassLoader
                            (container, container,
                                    this.getClass().getClassLoader());
                }
                catch (NamingException e)
                {
                    logger.error(sm.getString("naming.bindFailed", e));
                }
                if (container instanceof StandardServer)
                {
                    ((StandardServer) container).setGlobalNamingContext
                            (namingContext);
                }
            }

            initialized = true;

        } else if (event.getType() == Lifecycle.STOP_EVENT)
        {

            if (!initialized)
                return;

            // Setting the context in read/write mode
            ContextAccessController.setWritable(getName(), container);
            ContextBindings.unbindContext(container, container);

            if (container instanceof Context)
            {
                ContextBindings.unbindClassLoader
                        (container, container,
                                ((Container) container).getLoader().getClassLoader());
            }

            if (container instanceof Server)
            {
                namingResources.removePropertyChangeListener(this);
                ContextBindings.unbindClassLoader
                        (container, container,
                                this.getClass().getClassLoader());
            }

            ContextAccessController.unsetSecurityToken(getName(), container);
            ContextAccessController.unsetSecurityToken(container, container);

            // unregister mbeans.
            Collection<ObjectName> names = objectNames.values();
            for (ObjectName objectName : names)
            {
                Registry.getRegistry(null, null).unregisterComponent(objectName);
            }
            objectNames.clear();

            namingContext = null;
            envCtx = null;
            compCtx = null;
            initialized = false;

        }

    }


    // ---------------------------------------------- ContainerListener Methods


    /**
     * Acknowledge the occurrence of the specified event.
     * Note: Will never be called when the listener is associated to a Server,
     * since it is not a Container.
     *
     * @param event ContainerEvent that has occurred
     */
    public void containerEvent(ContainerEvent event)
    {

        if (!initialized)
            return;

        // Setting the context in read/write mode
        ContextAccessController.setWritable(getName(), container);

        String type = event.getType();

        if (type.equals("addEjb"))
        {

            String ejbName = (String) event.getData();
            if (ejbName != null)
            {
                ContextEjb ejb = namingResources.findEjb(ejbName);
                addEjb(ejb);
            }

        } else if (type.equals("addEnvironment"))
        {

            String environmentName = (String) event.getData();
            if (environmentName != null)
            {
                ContextEnvironment env =
                        namingResources.findEnvironment(environmentName);
                addEnvironment(env);
            }

        } else if (type.equals("addLocalEjb"))
        {

            String localEjbName = (String) event.getData();
            if (localEjbName != null)
            {
                ContextLocalEjb localEjb =
                        namingResources.findLocalEjb(localEjbName);
                addLocalEjb(localEjb);
            }

        } else if (type.equals("addResource"))
        {

            String resourceName = (String) event.getData();
            if (resourceName != null)
            {
                ContextResource resource =
                        namingResources.findResource(resourceName);
                addResource(resource);
            }

        } else if (type.equals("addResourceLink"))
        {

            String resourceLinkName = (String) event.getData();
            if (resourceLinkName != null)
            {
                ContextResourceLink resourceLink =
                        namingResources.findResourceLink(resourceLinkName);
                addResourceLink(resourceLink);
            }

        } else if (type.equals("addResourceEnvRef"))
        {

            String resourceEnvRefName = (String) event.getData();
            if (resourceEnvRefName != null)
            {
                ContextResourceEnvRef resourceEnvRef =
                        namingResources.findResourceEnvRef(resourceEnvRefName);
                addResourceEnvRef(resourceEnvRef);
            }

        } else if (type.equals("addService"))
        {

            String serviceName = (String) event.getData();
            if (serviceName != null)
            {
                ContextService service =
                        namingResources.findService(serviceName);
                addService(service);
            }

        } else if (type.equals("removeEjb"))
        {

            String ejbName = (String) event.getData();
            if (ejbName != null)
            {
                removeEjb(ejbName);
            }

        } else if (type.equals("removeEnvironment"))
        {

            String environmentName = (String) event.getData();
            if (environmentName != null)
            {
                removeEnvironment(environmentName);
            }

        } else if (type.equals("removeLocalEjb"))
        {

            String localEjbName = (String) event.getData();
            if (localEjbName != null)
            {
                removeLocalEjb(localEjbName);
            }

        } else if (type.equals("removeResource"))
        {

            String resourceName = (String) event.getData();
            if (resourceName != null)
            {
                removeResource(resourceName);
            }

        } else if (type.equals("removeResourceLink"))
        {

            String resourceLinkName = (String) event.getData();
            if (resourceLinkName != null)
            {
                removeResourceLink(resourceLinkName);
            }

        } else if (type.equals("removeResourceEnvRef"))
        {

            String resourceEnvRefName = (String) event.getData();
            if (resourceEnvRefName != null)
            {
                removeResourceEnvRef(resourceEnvRefName);
            }

        } else if (type.equals("removeService"))
        {

            String serviceName = (String) event.getData();
            if (serviceName != null)
            {
                removeService(serviceName);
            }

        }

        // Setting the context in read only mode
        ContextAccessController.setReadOnly(getName());

    }


    // ----------------------------------------- PropertyChangeListener Methods


    /**
     * Process property change events.
     *
     * @param event The property change event that has occurred
     */
    public void propertyChange(PropertyChangeEvent event)
    {

        if (!initialized)
            return;

        Object source = event.getSource();
        if (source == namingResources)
        {

            // Setting the context in read/write mode
            ContextAccessController.setWritable(getName(), container);

            processGlobalResourcesChange(event.getPropertyName(),
                    event.getOldValue(),
                    event.getNewValue());

            // Setting the context in read only mode
            ContextAccessController.setReadOnly(getName());

        }

    }


    // -------------------------------------------------------- Private Methods


    /**
     * Process a property change on the naming resources, by making the
     * corresponding addition or removal to the associated JNDI context.
     *
     * @param name     Property name of the change to be processed
     * @param oldValue The old value (or <code>null</code> if adding)
     * @param newValue The new value (or <code>null</code> if removing)
     */
    private void processGlobalResourcesChange(String name,
                                              Object oldValue,
                                              Object newValue)
    {

        if (name.equals("ejb"))
        {
            if (oldValue != null)
            {
                ContextEjb ejb = (ContextEjb) oldValue;
                if (ejb.getName() != null)
                {
                    removeEjb(ejb.getName());
                }
            }
            if (newValue != null)
            {
                ContextEjb ejb = (ContextEjb) newValue;
                if (ejb.getName() != null)
                {
                    addEjb(ejb);
                }
            }
        } else if (name.equals("environment"))
        {
            if (oldValue != null)
            {
                ContextEnvironment env = (ContextEnvironment) oldValue;
                if (env.getName() != null)
                {
                    removeEnvironment(env.getName());
                }
            }
            if (newValue != null)
            {
                ContextEnvironment env = (ContextEnvironment) newValue;
                if (env.getName() != null)
                {
                    addEnvironment(env);
                }
            }
        } else if (name.equals("localEjb"))
        {
            if (oldValue != null)
            {
                ContextLocalEjb ejb = (ContextLocalEjb) oldValue;
                if (ejb.getName() != null)
                {
                    removeLocalEjb(ejb.getName());
                }
            }
            if (newValue != null)
            {
                ContextLocalEjb ejb = (ContextLocalEjb) newValue;
                if (ejb.getName() != null)
                {
                    addLocalEjb(ejb);
                }
            }
        } else if (name.equals("resource"))
        {
            if (oldValue != null)
            {
                ContextResource resource = (ContextResource) oldValue;
                if (resource.getName() != null)
                {
                    removeResource(resource.getName());
                }
            }
            if (newValue != null)
            {
                ContextResource resource = (ContextResource) newValue;
                if (resource.getName() != null)
                {
                    addResource(resource);
                }
            }
        } else if (name.equals("resourceEnvRef"))
        {
            if (oldValue != null)
            {
                ContextResourceEnvRef resourceEnvRef =
                        (ContextResourceEnvRef) oldValue;
                if (resourceEnvRef.getName() != null)
                {
                    removeResourceEnvRef(resourceEnvRef.getName());
                }
            }
            if (newValue != null)
            {
                ContextResourceEnvRef resourceEnvRef =
                        (ContextResourceEnvRef) newValue;
                if (resourceEnvRef.getName() != null)
                {
                    addResourceEnvRef(resourceEnvRef);
                }
            }
        } else if (name.equals("resourceLink"))
        {
            if (oldValue != null)
            {
                ContextResourceLink rl = (ContextResourceLink) oldValue;
                if (rl.getName() != null)
                {
                    removeResourceLink(rl.getName());
                }
            }
            if (newValue != null)
            {
                ContextResourceLink rl = (ContextResourceLink) newValue;
                if (rl.getName() != null)
                {
                    addResourceLink(rl);
                }
            }
        } else if (name.equals("service"))
        {
            if (oldValue != null)
            {
                ContextService service = (ContextService) oldValue;
                if (service.getName() != null)
                {
                    removeService(service.getName());
                }
            }
            if (newValue != null)
            {
                ContextService service = (ContextService) newValue;
                if (service.getName() != null)
                {
                    addService(service);
                }
            }
        }


    }


    /**
     * Create and initialize the JNDI naming context.
     */
    private void createNamingContext()
            throws NamingException
    {

        // Creating the comp subcontext
        if (container instanceof Server)
        {
            compCtx = namingContext;
            envCtx = namingContext;
        } else
        {
            compCtx = namingContext.createSubcontext("comp");
            envCtx = compCtx.createSubcontext("env");
        }

        int i;

        if (log.isDebugEnabled())
            log.debug("Creating JNDI naming context");

        if (namingResources == null)
        {
            namingResources = new NamingResources();
            namingResources.setContainer(container);
        }

        // Resource links
        ContextResourceLink[] resourceLinks =
                namingResources.findResourceLinks();
        for (i = 0; i < resourceLinks.length; i++)
        {
            addResourceLink(resourceLinks[i]);
        }

        // Resources
        ContextResource[] resources = namingResources.findResources();
        for (i = 0; i < resources.length; i++)
        {
            addResource(resources[i]);
        }

        // Resources Env
        ContextResourceEnvRef[] resourceEnvRefs = namingResources.findResourceEnvRefs();
        for (i = 0; i < resourceEnvRefs.length; i++)
        {
            addResourceEnvRef(resourceEnvRefs[i]);
        }

        // Environment entries
        ContextEnvironment[] contextEnvironments =
                namingResources.findEnvironments();
        for (i = 0; i < contextEnvironments.length; i++)
        {
            addEnvironment(contextEnvironments[i]);
        }

        // EJB references
        ContextEjb[] ejbs = namingResources.findEjbs();
        for (i = 0; i < ejbs.length; i++)
        {
            addEjb(ejbs[i]);
        }

        // WebServices references
        ContextService[] services = namingResources.findServices();
        for (i = 0; i < services.length; i++)
        {
            addService(services[i]);
        }

        // Binding a User Transaction reference
        if (container instanceof Context)
        {
            try
            {
                Reference ref = new TransactionRef();
                compCtx.bind("UserTransaction", ref);
                ContextTransaction transaction = namingResources.getTransaction();
                if (transaction != null)
                {
                    Iterator params = transaction.listProperties();
                    while (params.hasNext())
                    {
                        String paramName = (String) params.next();
                        String paramValue = (String) transaction.getProperty(paramName);
                        StringRefAddr refAddr = new StringRefAddr(paramName, paramValue);
                        ref.add(refAddr);
                    }
                }
            }
            catch (NameAlreadyBoundException e)
            {
                // Ignore because UserTransaction was obviously 
                // added via ResourceLink
            }
            catch (NamingException e)
            {
                logger.error(sm.getString("naming.bindFailed", e));
            }
        }

        // Binding the resources directory context
        if (container instanceof Context)
        {
            try
            {
                compCtx.bind("Resources",
                        ((Container) container).getResources());
            }
            catch (NamingException e)
            {
                logger.error(sm.getString("naming.bindFailed", e));
            }
        }

    }


    /**
     * Create an <code>ObjectName</code> for this
     * <code>ContextResource</code> object.
     *
     * @param resource The resource
     * @return ObjectName The object name
     * @throws MalformedObjectNameException if a name cannot be created
     */
    protected ObjectName createObjectName(ContextResource resource)
            throws MalformedObjectNameException
    {

        String domain = null;
        if (container instanceof StandardServer)
        {
            domain = ((StandardServer) container).getDomain();
        } else if (container instanceof ContainerBase)
        {
            domain = ((ContainerBase) container).getDomain();
        }
        if (domain == null)
        {
            domain = "Catalina";
        }

        ObjectName name = null;
        String quotedResourceName = ObjectName.quote(resource.getName());
        if (container instanceof Server)
        {
            name = new ObjectName(domain + ":type=DataSource" +
                    ",class=" + resource.getType() +
                    ",name=" + quotedResourceName);
        } else if (container instanceof Context)
        {
            String path = ((Context) container).getPath();
            if (path.length() < 1)
                path = "/";
            Host host = (Host) ((Context) container).getParent();
            Engine engine = (Engine) host.getParent();
            Service service = engine.getService();
            name = new ObjectName(domain + ":type=DataSource" +
                    ",path=" + path +
                    ",host=" + host.getName() +
                    ",class=" + resource.getType() +
                    ",name=" + quotedResourceName);
        }

        return (name);

    }


    /**
     * Set the specified EJBs in the naming context.
     */
    public void addEjb(ContextEjb ejb)
    {

        // Create a reference to the EJB.
        Reference ref = new EjbRef
                (ejb.getType(), ejb.getHome(), ejb.getRemote(), ejb.getLink());
        // Adding the additional parameters, if any
        Iterator params = ejb.listProperties();
        while (params.hasNext())
        {
            String paramName = (String) params.next();
            String paramValue = (String) ejb.getProperty(paramName);
            StringRefAddr refAddr = new StringRefAddr(paramName, paramValue);
            ref.add(refAddr);
        }
        try
        {
            createSubcontexts(envCtx, ejb.getName());
            envCtx.bind(ejb.getName(), ref);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.bindFailed", e));
        }

    }


    /**
     * Set the specified environment entries in the naming context.
     */
    public void addEnvironment(ContextEnvironment env)
    {

        Object value = null;
        // Instantiating a new instance of the correct object type, and
        // initializing it.
        String type = env.getType();
        try
        {
            if (type.equals("java.lang.String"))
            {
                value = env.getValue();
            } else if (type.equals("java.lang.Byte"))
            {
                if (env.getValue() == null)
                {
                    value = new Byte((byte) 0);
                } else
                {
                    value = Byte.decode(env.getValue());
                }
            } else if (type.equals("java.lang.Short"))
            {
                if (env.getValue() == null)
                {
                    value = new Short((short) 0);
                } else
                {
                    value = Short.decode(env.getValue());
                }
            } else if (type.equals("java.lang.Integer"))
            {
                if (env.getValue() == null)
                {
                    value = new Integer(0);
                } else
                {
                    value = Integer.decode(env.getValue());
                }
            } else if (type.equals("java.lang.Long"))
            {
                if (env.getValue() == null)
                {
                    value = new Long(0);
                } else
                {
                    value = Long.decode(env.getValue());
                }
            } else if (type.equals("java.lang.Boolean"))
            {
                value = Boolean.valueOf(env.getValue());
            } else if (type.equals("java.lang.Double"))
            {
                if (env.getValue() == null)
                {
                    value = new Double(0);
                } else
                {
                    value = Double.valueOf(env.getValue());
                }
            } else if (type.equals("java.lang.Float"))
            {
                if (env.getValue() == null)
                {
                    value = new Float(0);
                } else
                {
                    value = Float.valueOf(env.getValue());
                }
            } else if (type.equals("java.lang.Character"))
            {
                if (env.getValue() == null)
                {
                    value = new Character((char) 0);
                } else
                {
                    if (env.getValue().length() == 1)
                    {
                        value = new Character(env.getValue().charAt(0));
                    } else
                    {
                        throw new IllegalArgumentException();
                    }
                }
            } else
            {
                logger.error(sm.getString("naming.invalidEnvEntryType", env.getName()));
            }
        }
        catch (NumberFormatException e)
        {
            logger.error(sm.getString("naming.invalidEnvEntryValue", env.getName()));
        }
        catch (IllegalArgumentException e)
        {
            logger.error(sm.getString("naming.invalidEnvEntryValue", env.getName()));
        }

        // Binding the object to the appropriate name
        if (value != null)
        {
            try
            {
                if (logger.isDebugEnabled())
                    logger.debug("  Adding environment entry " + env.getName());
                createSubcontexts(envCtx, env.getName());
                envCtx.bind(env.getName(), value);
            }
            catch (NamingException e)
            {
                logger.error(sm.getString("naming.invalidEnvEntryValue", e));
            }
        }

    }


    /**
     * Set the specified local EJBs in the naming context.
     */
    public void addLocalEjb(ContextLocalEjb localEjb)
    {


    }


    /**
     * Set the specified web service in the naming context.
     */
    public void addService(ContextService service)
    {

        if (service.getWsdlfile() != null)
        {
            URL wsdlURL = null;

            try
            {
                wsdlURL = new URL(service.getWsdlfile());
            }
            catch (MalformedURLException e)
            {
                wsdlURL = null;
            }
            if (wsdlURL == null)
            {
                try
                {
                    wsdlURL = ((Context) container).
                            getServletContext().
                            getResource(service.getWsdlfile());
                }
                catch (MalformedURLException e)
                {
                    wsdlURL = null;
                }
            }
            if (wsdlURL == null)
            {
                try
                {
                    wsdlURL = ((Context) container).
                            getServletContext().
                            getResource("/" + service.getWsdlfile());
                    logger.debug("  Changing service ref wsdl file for /"
                            + service.getWsdlfile());
                }
                catch (MalformedURLException e)
                {
                    logger.error(sm.getString("naming.wsdlFailed", e));
                }
            }
            if (wsdlURL == null)
                service.setWsdlfile(null);
            else
                service.setWsdlfile(wsdlURL.toString());
        }

        if (service.getJaxrpcmappingfile() != null)
        {
            URL jaxrpcURL = null;

            try
            {
                jaxrpcURL = new URL(service.getJaxrpcmappingfile());
            }
            catch (MalformedURLException e)
            {
                jaxrpcURL = null;
            }
            if (jaxrpcURL == null)
            {
                try
                {
                    jaxrpcURL = ((Context) container).
                            getServletContext().
                            getResource(service.getJaxrpcmappingfile());
                }
                catch (MalformedURLException e)
                {
                    jaxrpcURL = null;
                }
            }
            if (jaxrpcURL == null)
            {
                try
                {
                    jaxrpcURL = ((Context) container).
                            getServletContext().
                            getResource("/" + service.getJaxrpcmappingfile());
                    logger.debug("  Changing service ref jaxrpc file for /"
                            + service.getJaxrpcmappingfile());
                }
                catch (MalformedURLException e)
                {
                    logger.error(sm.getString("naming.wsdlFailed", e));
                }
            }
            if (jaxrpcURL == null)
                service.setJaxrpcmappingfile(null);
            else
                service.setJaxrpcmappingfile(jaxrpcURL.toString());
        }

        // Create a reference to the resource.
        Reference ref = new ServiceRef
                (service.getName(), service.getType(), service.getServiceqname(),
                        service.getWsdlfile(), service.getJaxrpcmappingfile());
        // Adding the additional port-component-ref, if any
        Iterator portcomponent = service.getServiceendpoints();
        while (portcomponent.hasNext())
        {
            String serviceendpoint = (String) portcomponent.next();
            StringRefAddr refAddr = new StringRefAddr(ServiceRef.SERVICEENDPOINTINTERFACE, serviceendpoint);
            ref.add(refAddr);
            String portlink = (String) service.getPortlink(serviceendpoint);
            refAddr = new StringRefAddr(ServiceRef.PORTCOMPONENTLINK, portlink);
            ref.add(refAddr);
        }
        // Adding the additional parameters, if any
        Iterator handlers = service.getHandlers();
        while (handlers.hasNext())
        {
            String handlername = (String) handlers.next();
            ContextHandler handler = (ContextHandler) service.getHandler(handlername);
            HandlerRef handlerRef = new HandlerRef(handlername, handler.getHandlerclass());
            Iterator localParts = handler.getLocalparts();
            while (localParts.hasNext())
            {
                String localPart = (String) localParts.next();
                String namespaceURI = (String) handler.getNamespaceuri(localPart);
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_LOCALPART, localPart));
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_NAMESPACE, namespaceURI));
            }
            Iterator params = handler.listProperties();
            while (params.hasNext())
            {
                String paramName = (String) params.next();
                String paramValue = (String) handler.getProperty(paramName);
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_PARAMNAME, paramName));
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_PARAMVALUE, paramValue));
            }
            for (int i = 0; i < handler.getSoapRolesSize(); i++)
            {
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_SOAPROLE, handler.getSoapRole(i)));
            }
            for (int i = 0; i < handler.getPortNamesSize(); i++)
            {
                handlerRef.add(new StringRefAddr(HandlerRef.HANDLER_PORTNAME, handler.getPortName(i)));
            }
            ((ServiceRef) ref).addHandler(handlerRef);
        }

        try
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("  Adding service ref "
                        + service.getName() + "  " + ref);
            }
            createSubcontexts(envCtx, service.getName());
            envCtx.bind(service.getName(), ref);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.bindFailed", e));
        }

    }


    /**
     * Set the specified resources in the naming context.
     */
    public void addResource(ContextResource resource)
    {

        // Create a reference to the resource.
        Reference ref = new ResourceRef
                (resource.getType(), resource.getDescription(),
                        resource.getScope(), resource.getAuth());
        // Adding the additional parameters, if any
        Iterator params = resource.listProperties();
        while (params.hasNext())
        {
            String paramName = (String) params.next();
            String paramValue = (String) resource.getProperty(paramName);
            StringRefAddr refAddr = new StringRefAddr(paramName, paramValue);
            ref.add(refAddr);
        }
        try
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("  Adding resource ref "
                        + resource.getName() + "  " + ref);
            }
            createSubcontexts(envCtx, resource.getName());
            envCtx.bind(resource.getName(), ref);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.bindFailed", e));
        }

        if ("javax.sql.DataSource".equals(ref.getClassName()))
        {
            try
            {
                ObjectName on = createObjectName(resource);
                Object actualResource = envCtx.lookup(resource.getName());
                Registry.getRegistry(null, null).registerComponent(actualResource, on, null);
                objectNames.put(resource.getName(), on);
            }
            catch (Exception e)
            {
                logger.warn(sm.getString("naming.jmxRegistrationFailed", e));
            }
        }

    }


    /**
     * Set the specified resources in the naming context.
     */
    public void addResourceEnvRef(ContextResourceEnvRef resourceEnvRef)
    {

        // Create a reference to the resource env.
        Reference ref = new ResourceEnvRef(resourceEnvRef.getType());
        // Adding the additional parameters, if any
        Iterator params = resourceEnvRef.listProperties();
        while (params.hasNext())
        {
            String paramName = (String) params.next();
            String paramValue = (String) resourceEnvRef.getProperty(paramName);
            StringRefAddr refAddr = new StringRefAddr(paramName, paramValue);
            ref.add(refAddr);
        }
        try
        {
            if (logger.isDebugEnabled())
                log.debug("  Adding resource env ref " + resourceEnvRef.getName());
            createSubcontexts(envCtx, resourceEnvRef.getName());
            envCtx.bind(resourceEnvRef.getName(), ref);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.bindFailed", e));
        }

    }


    /**
     * Set the specified resource link in the naming context.
     */
    public void addResourceLink(ContextResourceLink resourceLink)
    {

        // Create a reference to the resource.
        Reference ref = new ResourceLinkRef
                (resourceLink.getType(), resourceLink.getGlobal(),
                        resourceLink.getFactory(), null);
        Iterator i = resourceLink.listProperties();
        while (i.hasNext())
        {
            String key = i.next().toString();
            Object val = resourceLink.getProperty(key);
            if (val != null)
            {
                StringRefAddr refAddr = new StringRefAddr(key, val.toString());
                ref.add(refAddr);
            }
        }

        javax.naming.Context ctx =
                "UserTransaction".equals(resourceLink.getName())
                        ? compCtx : envCtx;
        try
        {
            if (logger.isDebugEnabled())
                log.debug("  Adding resource link " + resourceLink.getName());
            createSubcontexts(envCtx, resourceLink.getName());
            ctx.bind(resourceLink.getName(), ref);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.bindFailed", e));
        }

    }


    /**
     * Set the specified EJBs in the naming context.
     */
    public void removeEjb(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Set the specified environment entries in the naming context.
     */
    public void removeEnvironment(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Set the specified local EJBs in the naming context.
     */
    public void removeLocalEjb(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Set the specified web services in the naming context.
     */
    public void removeService(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Set the specified resources in the naming context.
     */
    public void removeResource(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

        ObjectName on = (ObjectName) objectNames.get(name);
        if (on != null)
        {
            Registry.getRegistry(null, null).unregisterComponent(on);
        }

    }


    /**
     * Set the specified resources in the naming context.
     */
    public void removeResourceEnvRef(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Set the specified resources in the naming context.
     */
    public void removeResourceLink(String name)
    {

        try
        {
            envCtx.unbind(name);
        }
        catch (NamingException e)
        {
            logger.error(sm.getString("naming.unbindFailed", e));
        }

    }


    /**
     * Create all intermediate subcontexts.
     */
    private void createSubcontexts(javax.naming.Context ctx, String name)
            throws NamingException
    {
        javax.naming.Context currentContext = ctx;
        StringTokenizer tokenizer = new StringTokenizer(name, "/");
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken();
            if ((!token.equals("")) && (tokenizer.hasMoreTokens()))
            {
                try
                {
                    currentContext = currentContext.createSubcontext(token);
                }
                catch (NamingException e)
                {
                    // Silent catch. Probably an object is already bound in
                    // the context.
                    currentContext =
                            (javax.naming.Context) currentContext.lookup(token);
                }
            }
        }
    }


}
