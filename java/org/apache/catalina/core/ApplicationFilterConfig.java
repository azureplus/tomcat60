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


import org.apache.AnnotationProcessor;
import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.Enumerator;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.log.SystemLogHandler;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.*;


/**
 * Implementation of a <code>javax.servlet.FilterConfig</code> useful in
 * managing the filter instances instantiated when a web application
 * is first started.
 *
 * @author Craig R. McClanahan
 */

public final class ApplicationFilterConfig implements FilterConfig, Serializable
{


    protected static StringManager sm =
            StringManager.getManager(Constants.Package);
    /**
     * Restricted filters (which can only be loaded by a privileged webapp).
     */
    protected static Properties restrictedFilters = null;

    // ----------------------------------------------------------- Constructors
    private static org.apache.juli.logging.Log log =
            LogFactory.getLog(ApplicationFilterConfig.class);


    // ----------------------------------------------------- Instance Variables
    /**
     * The Context with which we are associated.
     */
    private Context context = null;


    /**
     * The application Filter we are configured for.
     */
    private transient Filter filter = null;


    /**
     * The <code>FilterDef</code> that defines our associated Filter.
     */
    private FilterDef filterDef = null;
    /**
     * JMX registration name
     */
    private ObjectName oname;

    /**
     * Construct a new ApplicationFilterConfig for the specified filter
     * definition.
     *
     * @param context   The context with which we are associated
     * @param filterDef Filter definition for which a FilterConfig is to be
     *                  constructed
     * @throws ClassCastException        if the specified class does not implement
     *                                   the <code>javax.servlet.Filter</code> interface
     * @throws ClassNotFoundException    if the filter class cannot be found
     * @throws IllegalAccessException    if the filter class cannot be
     *                                   publicly instantiated
     * @throws InstantiationException    if an exception occurs while
     *                                   instantiating the filter object
     * @throws ServletException          if thrown by the filter's init() method
     * @throws NamingException
     * @throws InvocationTargetException
     */
    ApplicationFilterConfig(Context context, FilterDef filterDef)
            throws ClassCastException, ClassNotFoundException,
            IllegalAccessException, InstantiationException,
            ServletException, InvocationTargetException, NamingException
    {

        super();

        if (restrictedFilters == null)
        {
            restrictedFilters = new Properties();
            try
            {
                InputStream is =
                        this.getClass().getClassLoader().getResourceAsStream
                                ("org/apache/catalina/core/RestrictedFilters.properties");
                if (is != null)
                {
                    restrictedFilters.load(is);
                } else
                {
                    context.getLogger().error(sm.getString("applicationFilterConfig.restrictedFiltersResources"));
                }
            }
            catch (IOException e)
            {
                context.getLogger().error(sm.getString("applicationFilterConfig.restrictedServletsResources"), e);
            }
        }

        this.context = context;
        this.filterDef = filterDef;

        // Allocate a new filter instance
        getFilter();
    }

    // --------------------------------------------------- FilterConfig Methods

    /**
     * Return the name of the filter we are configuring.
     */
    public String getFilterName()
    {
        return (filterDef.getFilterName());
    }

    /**
     * Return the class of the filter we are configuring.
     */
    public String getFilterClass()
    {
        return filterDef.getFilterClass();
    }

    /**
     * Return a <code>String</code> containing the value of the named
     * initialization parameter, or <code>null</code> if the parameter
     * does not exist.
     *
     * @param name Name of the requested initialization parameter
     */
    public String getInitParameter(String name)
    {

        Map map = filterDef.getParameterMap();
        if (map == null)
            return (null);
        else
            return ((String) map.get(name));

    }


    /**
     * Return an <code>Enumeration</code> of the names of the initialization
     * parameters for this Filter.
     */
    public Enumeration getInitParameterNames()
    {

        Map map = filterDef.getParameterMap();
        if (map == null)
            return (new Enumerator(new ArrayList()));
        else
            return (new Enumerator(map.keySet()));

    }


    /**
     * Return the ServletContext of our associated web application.
     */
    public ServletContext getServletContext()
    {

        return (this.context.getServletContext());

    }


    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("ApplicationFilterConfig[");
        sb.append("name=");
        sb.append(filterDef.getFilterName());
        sb.append(", filterClass=");
        sb.append(filterDef.getFilterClass());
        sb.append("]");
        return (sb.toString());

    }

    // --------------------------------------------------------- Public Methods

    public Map<String, String> getFilterInitParameterMap()
    {
        return Collections.unmodifiableMap(filterDef.getParameterMap());
    }

    // -------------------------------------------------------- Package Methods


    /**
     * Return the application Filter we are configured for.
     *
     * @throws ClassCastException        if the specified class does not implement
     *                                   the <code>javax.servlet.Filter</code> interface
     * @throws ClassNotFoundException    if the filter class cannot be found
     * @throws IllegalAccessException    if the filter class cannot be
     *                                   publicly instantiated
     * @throws InstantiationException    if an exception occurs while
     *                                   instantiating the filter object
     * @throws ServletException          if thrown by the filter's init() method
     * @throws NamingException
     * @throws InvocationTargetException
     */
    Filter getFilter() throws ClassCastException, ClassNotFoundException,
            IllegalAccessException, InstantiationException, ServletException,
            InvocationTargetException, NamingException
    {

        // Return the existing filter instance, if any
        if (this.filter != null)
            return (this.filter);

        // Identify the class loader we will be using
        String filterClass = filterDef.getFilterClass();
        ClassLoader classLoader = null;
        if (filterClass.startsWith("org.apache.catalina."))
            classLoader = this.getClass().getClassLoader();
        else
            classLoader = context.getLoader().getClassLoader();

        ClassLoader oldCtxClassLoader =
                Thread.currentThread().getContextClassLoader();

        // Instantiate a new instance of this filter and return it
        Class clazz = classLoader.loadClass(filterClass);
        if (!isFilterAllowed(clazz))
        {
            throw new SecurityException
                    (sm.getString("applicationFilterConfig.privilegedFilter",
                            filterClass));
        }
        this.filter = (Filter) clazz.newInstance();
        if (!context.getIgnoreAnnotations())
        {
            if (context instanceof StandardContext)
            {
                AnnotationProcessor processor = ((StandardContext) context).getAnnotationProcessor();
                processor.processAnnotations(this.filter);
                processor.postConstruct(this.filter);
            }
        }
        if (context instanceof StandardContext &&
                ((StandardContext) context).getSwallowOutput())
        {
            try
            {
                SystemLogHandler.startCapture();
                filter.init(this);
            }
            finally
            {
                String log = SystemLogHandler.stopCapture();
                if (log != null && log.length() > 0)
                {
                    getServletContext().log(log);
                }
            }
        } else
        {
            filter.init(this);
        }

        // Expose filter via JMX
        registerJMX();

        return (this.filter);

    }


    /**
     * Return the filter definition we are configured for.
     */
    FilterDef getFilterDef()
    {

        return (this.filterDef);

    }


    /**
     * Return <code>true</code> if loading this filter is allowed.
     */
    protected boolean isFilterAllowed(Class filterClass)
    {

        // Privileged webapps may load all servlets without restriction
        if (context.getPrivileged())
        {
            return true;
        }

        Class clazz = filterClass;
        while (clazz != null && !clazz.getName().equals("javax.servlet.Filter"))
        {
            if ("restricted".equals(restrictedFilters.getProperty(clazz.getName())))
            {
                return (false);
            }
            clazz = clazz.getSuperclass();
        }

        return (true);

    }


    /**
     * Release the Filter instance associated with this FilterConfig,
     * if there is one.
     */
    void release()
    {

        unregisterJMX();

        if (this.filter != null)
        {
            try
            {
                if (Globals.IS_SECURITY_ENABLED)
                {
                    try
                    {
                        SecurityUtil.doAsPrivilege("destroy", filter);
                    }
                    finally
                    {
                        SecurityUtil.remove(filter);
                    }
                } else
                {
                    filter.destroy();
                }
            }
            catch (Throwable t)
            {
                ExceptionUtils.handleThrowable(t);
                context.getLogger().error(sm.getString(
                        "applicationFilterConfig.release",
                        filterDef.getFilterName(),
                        filterDef.getFilterClass()), t);
            }
            if (!context.getIgnoreAnnotations())
            {
                try
                {
                    ((StandardContext) context).getAnnotationProcessor().preDestroy(this.filter);
                }
                catch (Exception e)
                {
                    context.getLogger().error("ApplicationFilterConfig.preDestroy", e);
                }
            }
        }
        this.filter = null;

    }


    // -------------------------------------------------------- Private Methods


    private void registerJMX()
    {
        String parentName = context.getName();
        parentName = ("".equals(parentName)) ? "/" : parentName;

        String hostName = context.getParent().getName();
        hostName = (hostName == null) ? "DEFAULT" : hostName;

        // domain == engine name
        String domain = context.getParent().getParent().getName();

        String webMod = "//" + hostName + parentName;
        String onameStr = null;
        if (context instanceof StandardContext)
        {
            StandardContext standardContext = (StandardContext) context;
            onameStr = domain + ":j2eeType=Filter,name=" +
                    filterDef.getFilterName() + ",WebModule=" + webMod +
                    ",J2EEApplication=" +
                    standardContext.getJ2EEApplication() + ",J2EEServer=" +
                    standardContext.getJ2EEServer();
        } else
        {
            onameStr = domain + ":j2eeType=Filter,name=" +
                    filterDef.getFilterName() + ",WebModule=" + webMod;
        }
        try
        {
            oname = new ObjectName(onameStr);
            Registry.getRegistry(null, null).registerComponent(this, oname,
                    null);
        }
        catch (Exception ex)
        {
            log.info(sm.getString("applicationFilterConfig.jmxRegisterFail",
                    getFilterClass(), getFilterName()), ex);
        }
    }

    private void unregisterJMX()
    {
        // unregister this component
        if (oname != null)
        {
            try
            {
                Registry.getRegistry(null, null).unregisterComponent(oname);
                if (log.isDebugEnabled())
                    log.debug(sm.getString(
                            "applicationFilterConfig.jmxUnregister",
                            getFilterClass(), getFilterName()));
            }
            catch (Exception ex)
            {
                log.error(sm.getString(
                        "applicationFilterConfig.jmxUnregisterFail",
                        getFilterClass(), getFilterName()), ex);
            }
        }

    }
}
