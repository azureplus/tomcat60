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


package org.apache.tomcat.util.modeler;


import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.modeler.modules.ModelerSource;

import javax.management.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.util.*;

/*
   Issues:
   - exceptions - too many "throws Exception"
   - double check the interfaces 
   - start removing the use of the experimental methods in tomcat, then remove
     the methods ( before 1.1 final )
   - is the security enough to prevent Registry beeing used to avoid the permission
    checks in the mbean server ?
*/

/**
 * Registry for modeler MBeans.
 * <p/>
 * This is the main entry point into modeler. It provides methods to create
 * and manipulate model mbeans and simplify their use.
 * <p/>
 * Starting with version 1.1, this is no longer a singleton and the static
 * methods are strongly deprecated. In a container environment we can expect
 * different applications to use different registries.
 * <p/>
 * This class is itself an mbean.
 * <p/>
 * IMPORTANT: public methods not marked with @since x.x are experimental or
 * internal. Should not be used.
 *
 * @author Craig R. McClanahan
 * @author Costin Manolache
 */
public class Registry implements RegistryMBean, MBeanRegistration
{
    /**
     * The Log instance to which we will write our log messages.
     */
    private static Log log = LogFactory.getLog(Registry.class);

    // Support for the factory methods

    /**
     * Will be used to isolate different apps and enhance security.
     */
    private static HashMap perLoaderRegistries = null;

    /**
     * The registry instance created by our factory method the first time
     * it is called.
     */
    private static Registry registry = null;

    // Per registy fields

    /**
     * The <code>MBeanServer</code> instance that we will use to register
     * management beans.
     */
    private MBeanServer server = null;

    /**
     * The set of ManagedBean instances for the beans this registry
     * knows about, keyed by name.
     */
    private HashMap descriptors = new HashMap();

    /**
     * List of managed byeans, keyed by class name
     */
    private HashMap descriptorsByClass = new HashMap();

    // map to avoid duplicated searching or loading descriptors 
    private HashMap searchedPaths = new HashMap();

    private Object guard;

    // Id - small ints to use array access. No reset on stop()
    // Used for notifications
    private Hashtable idDomains = new Hashtable();
    private Hashtable ids = new Hashtable();


    // ----------------------------------------------------------- Constructors

    /**
     */
    public Registry()
    {
        super();
    }

    // -------------------- Static methods  --------------------
    // Factories

    /**
     * Factory method to create (if necessary) and return our
     * <code>Registry</code> instance.
     * <p/>
     * Use this method to obtain a Registry - all other static methods
     * are deprecated and shouldn't be used.
     * <p/>
     * The current version uses a static - future versions could use
     * the thread class loader.
     *
     * @param key   Support for application isolation. If null, the context class
     *              loader will be used ( if setUseContextClassLoader is called ) or the
     *              default registry is returned.
     * @param guard Prevent access to the registry by untrusted components
     * @since 1.1
     */
    public synchronized static Registry getRegistry(Object key, Object guard)
    {
        Registry localRegistry;
        if (perLoaderRegistries != null)
        {
            if (key == null)
                key = Thread.currentThread().getContextClassLoader();
            if (key != null)
            {
                localRegistry = (Registry) perLoaderRegistries.get(key);
                if (localRegistry == null)
                {
                    localRegistry = new Registry();
//                    localRegistry.key=key;
                    localRegistry.guard = guard;
                    perLoaderRegistries.put(key, localRegistry);
                    return localRegistry;
                }
                if (localRegistry.guard != null &&
                        localRegistry.guard != guard)
                {
                    return null; // XXX Should I throw a permission ex ? 
                }
                return localRegistry;
            }
        }

        // static 
        if (registry == null)
        {
            registry = new Registry();
        }
        if (registry.guard != null &&
                registry.guard != guard)
        {
            return null;
        }
        return (registry);
    }

    /**
     * Allow containers to isolate apps. Can be called only once.
     * It  is highly recommended you call this method if using Registry in
     * a container environment. The default is false for backward compatibility
     *
     * @param enable
     * @since 1.1
     */
    public static void setUseContextClassLoader(boolean enable)
    {
        if (enable)
        {
            perLoaderRegistries = new HashMap();
        }
    }

    // -------------------- Generic methods  --------------------

    /**
     * Factory method to create (if necessary) and return our
     * <code>MBeanServer</code> instance.
     *
     * @since 1.0
     * @deprecated Use the instance method
     */
    public static MBeanServer getServer()
    {
        return Registry.getRegistry().getMBeanServer();
    }

    /**
     * Set the <code>MBeanServer</code> to be utilized for our
     * registered management beans.
     *
     * @param mbeanServer The new <code>MBeanServer</code> instance
     * @since 1.0
     * @deprecated Use the instance method
     */
    public static void setServer(MBeanServer mbeanServer)
    {
        Registry.getRegistry().setMBeanServer(mbeanServer);
    }

    /**
     * Load the registry from the XML input found in the specified input
     * stream.
     *
     * @param stream InputStream containing the registry configuration
     *               information
     * @throws Exception if any parsing or processing error occurs
     * @since 1.0
     * @deprecated use normal class method instead
     */
    public static void loadRegistry(InputStream stream) throws Exception
    {
        Registry registry = getRegistry();
        registry.loadMetadata(stream);
    }

    /**
     * Get a "singelton" registry, or one per thread if setUseContextLoader
     * was called
     *
     * @since 1.0
     * @deprecated Not enough info - use the method that takes CL and domain
     */
    public synchronized static Registry getRegistry()
    {
        return getRegistry(null, null);
    }

    /**
     * Lifecycle method - clean up the registry metadata.
     * Called from resetMetadata().
     *
     * @since 1.1
     */
    public void stop()
    {
        descriptorsByClass = new HashMap();
        descriptors = new HashMap();
        searchedPaths = new HashMap();
    }

    /**
     * Load an extended mlet file. The source can be an URL, File or
     * InputStream.
     * <p/>
     * All mbeans will be instantiated, registered and the attributes will be
     * set. The result is a list of ObjectNames.
     *
     * @param source InputStream or URL of the file
     * @param cl     ClassLoader to be used to load the mbeans, or null to use the
     *               default JMX mechanism ( i.e. all registered loaders )
     * @return List of ObjectName for the loaded mbeans
     * @throws Exception
     * @since 1.1
     */
    public List loadMBeans(Object source, ClassLoader cl)
            throws Exception
    {
        return load("MbeansSource", source, null);
    }

    // -------------------- ID registry --------------------

    /**
     * Load descriptors. The source can be a File or URL or InputStream for the
     * descriptors file. In the case of File and URL, if the extension is ".ser"
     * a serialized version will be loaded.
     * <p/>
     * This method should be used to explicitely load metadata - but this is not
     * required in most cases. The registerComponent() method will find metadata
     * in the same pacakge.
     *
     * @param source
     */
    public void loadMetadata(Object source) throws Exception
    {
        loadDescriptors(null, source, null);
    }

    // -------------------- Metadata   --------------------
    // methods from 1.0

    /**
     * Register a bean by creating a modeler mbean and adding it to the
     * MBeanServer.
     * <p/>
     * If metadata is not loaded, we'll look up and read a file named
     * "mbeans-descriptors.ser" or "mbeans-descriptors.xml" in the same package
     * or parent.
     * <p/>
     * If the bean is an instance of DynamicMBean. it's metadata will be converted
     * to a model mbean and we'll wrap it - so modeler services will be supported
     * <p/>
     * If the metadata is still not found, introspection will be used to extract
     * it automatically.
     * <p/>
     * If an mbean is already registered under this name, it'll be first
     * unregistered.
     * <p/>
     * If the component implements MBeanRegistration, the methods will be called.
     * If the method has a method "setRegistry" that takes a RegistryMBean as
     * parameter, it'll be called with the current registry.
     *
     * @param bean  Object to be registered
     * @param oname Name used for registration
     * @param type  The type of the mbean, as declared in mbeans-descriptors. If
     *              null, the name of the class will be used. This can be used as a hint or
     *              by subclasses.
     * @since 1.1
     */
    public void registerComponent(Object bean, String oname, String type)
            throws Exception
    {
        registerComponent(bean, new ObjectName(oname), type);
    }

    /**
     * Unregister a component. We'll first check if it is registered,
     * and mask all errors. This is mostly a helper.
     *
     * @param oname
     * @since 1.1
     */
    public void unregisterComponent(String oname)
    {
        try
        {
            unregisterComponent(new ObjectName(oname));
        }
        catch (MalformedObjectNameException e)
        {
            log.info("Error creating object name " + e);
        }
    }

    /**
     * Invoke a operation on a list of mbeans. Can be used to implement
     * lifecycle operations.
     *
     * @param mbeans    list of ObjectName on which we'll invoke the operations
     * @param operation Name of the operation ( init, start, stop, etc)
     * @param failFirst If false, exceptions will be ignored
     * @throws Exception
     * @since 1.1
     */
    public void invoke(List mbeans, String operation, boolean failFirst)
            throws Exception
    {
        if (mbeans == null)
        {
            return;
        }
        Iterator itr = mbeans.iterator();
        while (itr.hasNext())
        {
            Object current = itr.next();
            ObjectName oN = null;
            try
            {
                if (current instanceof ObjectName)
                {
                    oN = (ObjectName) current;
                }
                if (current instanceof String)
                {
                    oN = new ObjectName((String) current);
                }
                if (oN == null)
                {
                    continue;
                }
                if (getMethodInfo(oN, operation) == null)
                {
                    continue;
                }
                getMBeanServer().invoke(oN, operation,
                        new Object[]{}, new String[]{});

            }
            catch (Exception t)
            {
                if (failFirst) throw t;
                log.info("Error initializing " + current + " " + t.toString());
            }
        }
    }

    /**
     * Return an int ID for faster access. Will be used for notifications
     * and for other operations we want to optimize.
     *
     * @param domain Namespace
     * @param name   Type of the notification
     * @return An unique id for the domain:name combination
     * @since 1.1
     */
    public synchronized int getId(String domain, String name)
    {
        if (domain == null)
        {
            domain = "";
        }
        Hashtable domainTable = (Hashtable) idDomains.get(domain);
        if (domainTable == null)
        {
            domainTable = new Hashtable();
            idDomains.put(domain, domainTable);
        }
        if (name == null)
        {
            name = "";
        }
        Integer i = (Integer) domainTable.get(name);

        if (i != null)
        {
            return i.intValue();
        }

        int id[] = (int[]) ids.get(domain);
        if (id == null)
        {
            id = new int[1];
            ids.put(domain, id);
        }
        int code = id[0]++;
        domainTable.put(name, new Integer(code));
        return code;
    }

    /**
     * Add a new bean metadata to the set of beans known to this registry.
     * This is used by internal components.
     *
     * @param bean The managed bean to be added
     * @since 1.0
     */
    public void addManagedBean(ManagedBean bean)
    {
        // XXX Use group + name
        descriptors.put(bean.getName(), bean);
        if (bean.getType() != null)
        {
            descriptorsByClass.put(bean.getType(), bean);
        }
    }

    // -------------------- Deprecated 1.0 methods  --------------------

    /**
     * Find and return the managed bean definition for the specified
     * bean name, if any; otherwise return <code>null</code>.
     *
     * @param name Name of the managed bean to be returned. Since 1.1, both
     *             short names or the full name of the class can be used.
     * @since 1.0
     */
    public ManagedBean findManagedBean(String name)
    {
        // XXX Group ?? Use Group + Type
        ManagedBean mb = ((ManagedBean) descriptors.get(name));
        if (mb == null)
            mb = (ManagedBean) descriptorsByClass.get(name);
        return mb;
    }

    /**
     * Return the set of bean names for all managed beans known to
     * this registry.
     *
     * @since 1.0
     */
    public String[] findManagedBeans()
    {
        return ((String[]) descriptors.keySet().toArray(new String[0]));
    }

    /**
     * Return the set of bean names for all managed beans known to
     * this registry that belong to the specified group.
     *
     * @param group Name of the group of interest, or <code>null</code>
     *              to select beans that do <em>not</em> belong to a group
     * @since 1.0
     */
    public String[] findManagedBeans(String group)
    {

        ArrayList results = new ArrayList();
        Iterator items = descriptors.values().iterator();
        while (items.hasNext())
        {
            ManagedBean item = (ManagedBean) items.next();
            if ((group == null) && (item.getGroup() == null))
            {
                results.add(item.getName());
            } else if (group.equals(item.getGroup()))
            {
                results.add(item.getName());
            }
        }
        String values[] = new String[results.size()];
        return ((String[]) results.toArray(values));

    }

    /**
     * Remove an existing bean from the set of beans known to this registry.
     *
     * @param bean The managed bean to be removed
     * @since 1.0
     */
    public void removeManagedBean(ManagedBean bean)
    {
        // TODO: change this to use group/name
        descriptors.remove(bean.getName());
        descriptorsByClass.remove(bean.getType());
    }

    // -------------------- Helpers  --------------------

    /**
     * Get the type of an attribute of the object, from the metadata.
     *
     * @param oname
     * @param attName
     * @return null if metadata about the attribute is not found
     * @since 1.1
     */
    public String getType(ObjectName oname, String attName)
    {
        String type = null;
        MBeanInfo info = null;
        try
        {
            info = server.getMBeanInfo(oname);
        }
        catch (Exception e)
        {
            log.info("Can't find metadata for object" + oname);
            return null;
        }

        MBeanAttributeInfo attInfo[] = info.getAttributes();
        for (int i = 0; i < attInfo.length; i++)
        {
            if (attName.equals(attInfo[i].getName()))
            {
                type = attInfo[i].getType();
                return type;
            }
        }
        return null;
    }

    /**
     * Find the operation info for a method
     *
     * @param oname
     * @param opName
     * @return the operation info for the specified operation
     */
    public MBeanOperationInfo getMethodInfo(ObjectName oname, String opName)
    {
        String type = null;
        MBeanInfo info = null;
        try
        {
            info = server.getMBeanInfo(oname);
        }
        catch (Exception e)
        {
            log.info("Can't find metadata " + oname);
            return null;
        }
        MBeanOperationInfo attInfo[] = info.getOperations();
        for (int i = 0; i < attInfo.length; i++)
        {
            if (opName.equals(attInfo[i].getName()))
            {
                return attInfo[i];
            }
        }
        return null;
    }

    /**
     * Unregister a component. This is just a helper that
     * avoids exceptions by checking if the mbean is already registered
     *
     * @param oname
     */
    public void unregisterComponent(ObjectName oname)
    {
        try
        {
            if (getMBeanServer().isRegistered(oname))
            {
                getMBeanServer().unregisterMBean(oname);
            }
        }
        catch (Throwable t)
        {
            log.error("Error unregistering mbean ", t);
        }
    }

    /**
     * Factory method to create (if necessary) and return our
     * <code>MBeanServer</code> instance.
     */
    public synchronized MBeanServer getMBeanServer()
    {
        long t1 = System.currentTimeMillis();

        if (server == null)
        {
            if (MBeanServerFactory.findMBeanServer(null).size() > 0)
            {
                server = (MBeanServer) MBeanServerFactory.findMBeanServer(null).get(0);
                if (log.isDebugEnabled())
                {
                    log.debug("Using existing MBeanServer " + (System.currentTimeMillis() - t1));
                }
            } else
            {
                server = ManagementFactory.getPlatformMBeanServer();
                if (log.isDebugEnabled())
                {
                    log.debug("Creating MBeanServer" + (System.currentTimeMillis() - t1));
                }
            }
        }
        return (server);
    }

    /**
     * Set the <code>MBeanServer</code> to be utilized for our
     * registered management beans.
     *
     * @param server The new <code>MBeanServer</code> instance
     */
    public void setMBeanServer(MBeanServer server)
    {
        this.server = server;
    }

    /**
     * Find or load metadata.
     */
    public ManagedBean findManagedBean(Object bean, Class beanClass, String type)
            throws Exception
    {
        if (bean != null && beanClass == null)
        {
            beanClass = bean.getClass();
        }

        if (type == null)
        {
            type = beanClass.getName();
        }

        // first look for existing descriptor
        ManagedBean managed = findManagedBean(type);

        // Search for a descriptor in the same package
        if (managed == null)
        {
            // check package and parent packages
            if (log.isDebugEnabled())
            {
                log.debug("Looking for descriptor ");
            }
            findDescriptor(beanClass, type);

            managed = findManagedBean(type);
        }

        if (bean instanceof DynamicMBean)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Dynamic mbean support ");
            }
            // Dynamic mbean
            loadDescriptors("MbeansDescriptorsDynamicMBeanSource",
                    bean, type);

            managed = findManagedBean(type);
        }

        // Still not found - use introspection
        if (managed == null)
        {
            if (log.isDebugEnabled())
            {
                log.debug("Introspecting ");
            }

            // introspection
            loadDescriptors("MbeansDescriptorsIntrospectionSource",
                    beanClass, type);

            managed = findManagedBean(type);
            if (managed == null)
            {
                log.warn("No metadata found for " + type);
                return null;
            }
            managed.setName(type);
            addManagedBean(managed);
        }
        return managed;
    }

    /**
     * EXPERIMENTAL Convert a string to object, based on type. Used by several
     * components. We could provide some pluggability. It is here to keep
     * things consistent and avoid duplication in other tasks
     *
     * @param type  Fully qualified class name of the resulting value
     * @param value String value to be converted
     * @return Converted value
     */
    public Object convertValue(String type, String value)
    {
        Object objValue = value;

        if (type == null || "java.lang.String".equals(type))
        {
            // string is default
            objValue = value;
        } else if ("javax.management.ObjectName".equals(type) ||
                "ObjectName".equals(type))
        {
            try
            {
                objValue = new ObjectName(value);
            }
            catch (MalformedObjectNameException e)
            {
                return null;
            }
        } else if ("java.lang.Integer".equals(type) ||
                "int".equals(type))
        {
            objValue = new Integer(value);
        } else if ("java.lang.Long".equals(type) ||
                "long".equals(type))
        {
            objValue = new Long(value);
        } else if ("java.lang.Boolean".equals(type) ||
                "boolean".equals(type))
        {
            objValue = new Boolean(value);
        }
        return objValue;
    }

    /**
     * Experimental.
     *
     * @param sourceType
     * @param source
     * @param param
     * @return List of descriptors
     * @throws Exception
     * @deprecated bad interface, mixing of metadata and mbeans
     */
    public List load(String sourceType, Object source, String param)
            throws Exception
    {
        if (log.isTraceEnabled())
        {
            log.trace("load " + source);
        }
        String location = null;
        String type = null;
        Object inputsource = null;

        if (source instanceof DynamicMBean)
        {
            sourceType = "MbeansDescriptorsDynamicMBeanSource";
            inputsource = source;
        } else if (source instanceof URL)
        {
            URL url = (URL) source;
            location = url.toString();
            type = param;
            inputsource = url.openStream();
            if (sourceType == null)
            {
                sourceType = sourceTypeFromExt(location);
            }
        } else if (source instanceof File)
        {
            location = ((File) source).getAbsolutePath();
            inputsource = new FileInputStream((File) source);
            type = param;
            if (sourceType == null)
            {
                sourceType = sourceTypeFromExt(location);
            }
        } else if (source instanceof InputStream)
        {
            type = param;
            inputsource = source;
        } else if (source instanceof Class)
        {
            location = ((Class) source).getName();
            type = param;
            inputsource = source;
            if (sourceType == null)
            {
                sourceType = "MbeansDescriptorsIntrospectionSource";
            }
        }

        if (sourceType == null)
        {
            sourceType = "MbeansDescriptorsDigesterSource";
        }
        ModelerSource ds = getModelerSource(sourceType);
        List mbeans = ds.loadDescriptors(this, location, type, inputsource);

        return mbeans;
    }

    private String sourceTypeFromExt(String s)
    {
        if (s.endsWith(".ser"))
        {
            return "MbeansDescriptorsSerSource";
        } else if (s.endsWith(".xml"))
        {
            return "MbeansDescriptorsDigesterSource";
        }
        return null;
    }

    /**
     * Register a component
     * XXX make it private
     *
     * @param bean
     * @param oname
     * @param type
     * @throws Exception
     */
    public void registerComponent(Object bean, ObjectName oname, String type)
            throws Exception
    {
        if (log.isDebugEnabled())
        {
            log.debug("Managed= " + oname);
        }

        if (bean == null)
        {
            log.error("Null component " + oname);
            return;
        }

        try
        {
            if (type == null)
            {
                type = bean.getClass().getName();
            }

            ManagedBean managed = findManagedBean(bean.getClass(), type);

            // The real mbean is created and registered
            DynamicMBean mbean = managed.createMBean(bean);

            if (getMBeanServer().isRegistered(oname))
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Unregistering existing component " + oname);
                }
                getMBeanServer().unregisterMBean(oname);
            }

            getMBeanServer().registerMBean(mbean, oname);
        }
        catch (Exception ex)
        {
            log.error("Error registering " + oname, ex);
            throw ex;
        }
    }

    /**
     * Lookup the component descriptor in the package and
     * in the parent packages.
     *
     * @param packageName
     */
    public void loadDescriptors(String packageName, ClassLoader classLoader)
    {
        String res = packageName.replace('.', '/');

        if (log.isTraceEnabled())
        {
            log.trace("Finding descriptor " + res);
        }

        if (searchedPaths.get(packageName) != null)
        {
            return;
        }
        String descriptors = res + "/mbeans-descriptors.ser";

        URL dURL = classLoader.getResource(descriptors);

        if (dURL == null)
        {
            descriptors = res + "/mbeans-descriptors.xml";
            dURL = classLoader.getResource(descriptors);
        }
        if (dURL == null)
        {
            return;
        }

        log.debug("Found " + dURL);
        searchedPaths.put(packageName, dURL);
        try
        {
            if (descriptors.endsWith(".xml"))
                loadDescriptors("MbeansDescriptorsDigesterSource", dURL, null);
            else
                loadDescriptors("MbeansDescriptorsSerSource", dURL, null);
            return;
        }
        catch (Exception ex)
        {
            log.error("Error loading " + dURL);
        }

        return;
    }

    /**
     * Experimental. Will become private, some code may still use it
     *
     * @param sourceType
     * @param source
     * @param param
     * @throws Exception
     * @deprecated
     */
    public void loadDescriptors(String sourceType, Object source, String param)
            throws Exception
    {
        List mbeans = load(sourceType, source, param);
        if (mbeans == null) return;

        Iterator itr = mbeans.iterator();
        while (itr.hasNext())
        {
            Object mb = itr.next();
            if (mb instanceof ManagedBean)
            {
                addManagedBean((ManagedBean) mb);
            }
        }
    }

    /**
     * Lookup the component descriptor in the package and
     * in the parent packages.
     *
     * @param beanClass
     * @param type
     */
    private void findDescriptor(Class beanClass, String type)
    {
        if (type == null)
        {
            type = beanClass.getName();
        }
        ClassLoader classLoader = null;
        if (beanClass != null)
        {
            classLoader = beanClass.getClassLoader();
        }
        if (classLoader == null)
        {
            classLoader = Thread.currentThread().getContextClassLoader();
        }
        if (classLoader == null)
        {
            classLoader = this.getClass().getClassLoader();
        }

        String className = type;
        String pkg = className;
        while (pkg.indexOf(".") > 0)
        {
            int lastComp = pkg.lastIndexOf(".");
            if (lastComp <= 0) return;
            pkg = pkg.substring(0, lastComp);
            if (searchedPaths.get(pkg) != null)
            {
                return;
            }
            loadDescriptors(pkg, classLoader);
        }
        return;
    }


    // -------------------- Registration  --------------------

    private ModelerSource getModelerSource(String type)
            throws Exception
    {
        if (type == null) type = "MbeansDescriptorsDigesterSource";
        if (type.indexOf(".") < 0)
        {
            type = "org.apache.tomcat.util.modeler.modules." + type;
        }

        Class c = Class.forName(type);
        ModelerSource ds = (ModelerSource) c.newInstance();
        return ds;
    }

    public ObjectName preRegister(MBeanServer server,
                                  ObjectName name) throws Exception
    {
        this.server = server;
        return name;
    }

    public void postRegister(Boolean registrationDone)
    {
    }

    public void preDeregister() throws Exception
    {
    }


    // -------------------- DEPRECATED METHODS  --------------------
    // May still be used in tomcat 
    // Never part of an official release

    public void postDeregister()
    {
    }

    /**
     * Called by a registry or by the container to unload a loader
     *
     * @param loader
     */
    public void unregisterRegistry(ClassLoader loader)
    {
        // XXX Cleanup ?
        perLoaderRegistries.remove(loader);
    }

    public ManagedBean findManagedBean(Class beanClass, String type)
            throws Exception
    {
        return findManagedBean(null, beanClass, type);
    }

    public void resetMetadata()
    {
        stop();
    }

    /**
     * Load the registry from the XML input found in the specified input
     * stream.
     *
     * @param source Source to be used to load. Can be an InputStream or URL.
     * @throws Exception if any parsing or processing error occurs
     */
    public void loadDescriptors(Object source)
            throws Exception
    {
        loadDescriptors("MbeansDescriptorsDigesterSource", source, null);
    }

    /**
     * @deprecated - may still be used in code using pre-1.1 builds
     */
    public void registerComponent(Object bean, String domain, String type,
                                  String name)
            throws Exception
    {
        StringBuffer sb = new StringBuffer();
        sb.append(domain).append(":");
        sb.append(name);
        String nameStr = sb.toString();
        ObjectName oname = new ObjectName(nameStr);
        registerComponent(bean, oname, type);
    }


    // should be removed
    public void unregisterComponent(String domain, String name)
    {
        try
        {
            ObjectName oname = new ObjectName(domain + ":" + name);

            // XXX remove from our tables.
            getMBeanServer().unregisterMBean(oname);
        }
        catch (Throwable t)
        {
            log.error("Error unregistering mbean ", t);
        }
    }


    /**
     * Load the registry from a cached .ser file. This is typically 2-3 times
     * faster than parsing the XML.
     *
     * @param source Source to be used to load. Can be an InputStream or URL.
     * @throws Exception if any parsing or processing error occurs
     * @deprecated Loaded automatically or using a File or Url ending in .ser
     */
    public void loadCachedDescriptors(Object source)
            throws Exception
    {
        loadDescriptors("MbeansDescriptorsSerSource", source, null);
    }
}
