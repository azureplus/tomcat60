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


package org.apache.catalina.loader;

import org.apache.catalina.Globals;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.util.StringManager;
import org.apache.jasper.servlet.JasperLoader;
import org.apache.naming.JndiPermission;
import org.apache.naming.resources.ProxyDirContext;
import org.apache.naming.resources.Resource;
import org.apache.naming.resources.ResourceAttributes;
import org.apache.tomcat.util.IntrospectionUtils;

import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.io.*;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.*;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Specialized web application class loader.
 * <p/>
 * This class loader is a full reimplementation of the
 * <code>URLClassLoader</code> from the JDK. It is designed to be fully
 * compatible with a normal <code>URLClassLoader</code>, although its internal
 * behavior may be completely different.
 * <p/>
 * <strong>IMPLEMENTATION NOTE</strong> - This class loader faithfully follows
 * the delegation model recommended in the specification. The system class
 * loader will be queried first, then the local repositories, and only then
 * delegation to the parent class loader will occur. This allows the web
 * application to override any shared class except the classes from J2SE.
 * Special handling is provided from the JAXP XML parser interfaces, the JNDI
 * interfaces, and the classes from the servlet API, which are never loaded
 * from the webapp repository.
 * <p/>
 * <strong>IMPLEMENTATION NOTE</strong> - Due to limitations in Jasper
 * compilation technology, any repository which contains classes from
 * the servlet API will be ignored by the class loader.
 * <p/>
 * <strong>IMPLEMENTATION NOTE</strong> - The class loader generates source
 * URLs which include the full JAR URL when a class is loaded from a JAR file,
 * which allows setting security permission at the class level, even when a
 * class is contained inside a JAR.
 * <p/>
 * <strong>IMPLEMENTATION NOTE</strong> - Local repositories are searched in
 * the order they are added via the initial constructor and/or any subsequent
 * calls to <code>addRepository()</code> or <code>addJar()</code>.
 * <p/>
 * <strong>IMPLEMENTATION NOTE</strong> - No check for sealing violations or
 * security is made unless a security manager is present.
 *
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 */
public class WebappClassLoader
        extends URLClassLoader
        implements Reloader, Lifecycle
{

    public static final boolean ENABLE_CLEAR_REFERENCES =
            Boolean.valueOf(System.getProperty("org.apache.catalina.loader.WebappClassLoader.ENABLE_CLEAR_REFERENCES", "true")).booleanValue();
    /**
     * The set of trigger classes that will cause a proposed repository not
     * to be added if this class is visible to the class loader that loaded
     * this factory class.  Typically, trigger classes will be listed for
     * components that have been integrated into the JDK for later versions,
     * but where the corresponding JAR files are required to run on
     * earlier versions.
     */
    protected static final String[] triggers = {
            "javax.servlet.Servlet"                     // Servlet API
    };
    /**
     * Set of package names which are not allowed to be loaded from a webapp
     * class loader without delegating first.
     */
    protected static final String[] packageTriggers = {
    };
    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
            StringManager.getManager(Constants.Package);
    /**
     * List of ThreadGroup names to ignore when scanning for web application
     * started threads that need to be shut down.
     */
    private static final List<String> JVM_THREAD_GROUP_NAMES =
            new ArrayList<String>();
    private static final String JVN_THREAD_GROUP_SYSTEM = "system";
    protected static org.apache.juli.logging.Log log =
            org.apache.juli.logging.LogFactory.getLog(WebappClassLoader.class);

    static
    {
        JVM_THREAD_GROUP_NAMES.add(JVN_THREAD_GROUP_SYSTEM);
        JVM_THREAD_GROUP_NAMES.add("RMI Runtime");
    }


    // ------------------------------------------------------- Static Variables

    /**
     * Associated directory context giving access to the resources in this
     * webapp.
     */
    protected DirContext resources = null;
    /**
     * The cache of ResourceEntry for classes and resources we have loaded,
     * keyed by resource name.
     */
    protected HashMap resourceEntries = new HashMap();
    /**
     * The list of not found resources.
     */
    protected HashMap<String, String> notFoundResources =
            new LinkedHashMap<String, String>()
            {
                private static final long serialVersionUID = 1L;

                protected boolean removeEldestEntry(
                        Map.Entry<String, String> eldest)
                {
                    return size() > 1000;
                }
            };
    /**
     * Should this class loader delegate to the parent class loader
     * <strong>before</strong> searching its own repositories (i.e. the
     * usual Java2 delegation model)?  If set to <code>false</code>,
     * this class loader will search its own repositories first, and
     * delegate to the parent only if the class or resource is not
     * found locally.
     */
    protected boolean delegate = false;


    // ----------------------------------------------------------- Constructors
    /**
     * Last time a JAR was accessed.
     */
    protected long lastJarAccessed = 0L;
    /**
     * The list of local repositories, in the order they should be searched
     * for locally loaded classes or resources.
     */
    protected String[] repositories = new String[0];


    // ----------------------------------------------------- Instance Variables
    /**
     * Repositories URLs, used to cache the result of getURLs.
     */
    protected URL[] repositoryURLs = null;
    /**
     * Repositories translated as path in the work directory (for Jasper
     * originally), but which is used to generate fake URLs should getURLs be
     * called.
     */
    protected File[] files = new File[0];
    /**
     * The list of JARs, in the order they should be searched
     * for locally loaded classes or resources.
     */
    protected JarFile[] jarFiles = new JarFile[0];
    /**
     * The list of JARs, in the order they should be searched
     * for locally loaded classes or resources.
     */
    protected File[] jarRealFiles = new File[0];
    /**
     * The path which will be monitored for added Jar files.
     */
    protected String jarPath = null;
    /**
     * The list of JARs, in the order they should be searched
     * for locally loaded classes or resources.
     */
    protected String[] jarNames = new String[0];
    /**
     * The list of JARs last modified dates, in the order they should be
     * searched for locally loaded classes or resources.
     */
    protected long[] lastModifiedDates = new long[0];
    /**
     * The list of resources which should be checked when checking for
     * modifications.
     */
    protected String[] paths = new String[0];
    /**
     * A list of read File and Jndi Permission's required if this loader
     * is for a web application context.
     */
    protected ArrayList permissionList = new ArrayList();
    /**
     * Path where resources loaded from JARs will be extracted.
     */
    protected File loaderDir = null;
    protected String canonicalLoaderDir = null;
    /**
     * The PermissionCollection for each CodeSource for a web
     * application context.
     */
    protected HashMap loaderPC = new HashMap();
    /**
     * Instance of the SecurityManager installed.
     */
    protected SecurityManager securityManager = null;
    /**
     * The parent class loader.
     */
    protected ClassLoader parent = null;
    /**
     * The system class loader.
     */
    protected ClassLoader system = null;
    /**
     * Has this component been started?
     */
    protected boolean started = false;
    /**
     * Has external repositories.
     */
    protected boolean hasExternalRepositories = false;
    /**
     * Search external repositories first
     */
    protected boolean searchExternalFirst = false;
    /**
     * need conversion for properties files
     */
    protected boolean needConvert = false;
    /**
     * All permission.
     */
    protected Permission allPermission = new java.security.AllPermission();
    /**
     * Use anti JAR locking code, which does URL rerouting when accessing
     * resources.
     */
    boolean antiJARLocking = false;
    /**
     * Should Tomcat attempt to terminate threads that have been started by the
     * web application? Stopping threads is performed via the deprecated (for
     * good reason) <code>Thread.stop()</code> method and is likely to result in
     * instability. As such, enabling this should be viewed as an option of last
     * resort in a development environment and is not recommended in a
     * production environment. If not specified, the default value of
     * <code>false</code> will be used.
     */
    private boolean clearReferencesStopThreads = false;
    /**
     * Should Tomcat attempt to terminate any {@link java.util.TimerThread}s
     * that have been started by the web application? If not specified, the
     * default value of <code>false</code> will be used.
     */
    private boolean clearReferencesStopTimerThreads = false;
    /**
     * Should Tomcat attempt to clear any ThreadLocal objects that are instances
     * of classes loaded by this class loader. Failure to remove any such
     * objects will result in a memory leak on web application stop, undeploy or
     * reload. It is disabled by default since the clearing of the ThreadLocal
     * objects is not performed in a thread-safe manner.
     */
    private boolean clearReferencesThreadLocals = false;
    /**
     * Should Tomcat call {@link org.apache.juli.logging.LogFactory#release()}
     * when the class loader is stopped? If not specified, the default value
     * of <code>true</code> is used. Changing the default setting is likely to
     * lead to memory leaks and other issues.
     */
    private boolean clearReferencesLogFactoryRelease = true;
    /**
     * If an HttpClient keep-alive timer thread has been started by this web
     * application and is still running, should Tomcat change the context class
     * loader from the current {@link WebappClassLoader} to
     * {@link WebappClassLoader#parent} to prevent a memory leak? Note that the
     * keep-alive timer thread will stop on its own once the keep-alives all
     * expire however, on a busy system that might not happen for some time.
     */
    private boolean clearReferencesHttpClientKeepAliveThread = true;
    /**
     * Name of associated context used with logging and JMX to associate with
     * the right web application. Particularly useful for the clear references
     * messages. Defaults to unknown but if standard Tomcat components are used
     * it will be updated during initialisation from the resources.
     */
    private String contextName = "unknown";

    /**
     * Construct a new ClassLoader with no defined repositories and no
     * parent ClassLoader.
     */
    public WebappClassLoader()
    {

        super(new URL[0]);
        this.parent = getParent();
        system = getSystemClassLoader();
        securityManager = System.getSecurityManager();

        if (securityManager != null)
        {
            refreshPolicy();
        }

    }

    /**
     * Construct a new ClassLoader with no defined repositories and the given
     * parent ClassLoader.
     *
     * @param parent Our parent class loader
     */
    public WebappClassLoader(ClassLoader parent)
    {

        super(new URL[0], parent);

        this.parent = getParent();

        system = getSystemClassLoader();
        securityManager = System.getSecurityManager();

        if (securityManager != null)
        {
            refreshPolicy();
        }
    }

    /**
     * Delete the specified directory, including all of its contents and
     * subdirectories recursively.
     *
     * @param dir File object representing the directory to be deleted
     */
    protected static void deleteDir(File dir)
    {

        String files[] = dir.list();
        if (files == null)
        {
            files = new String[0];
        }
        for (int i = 0; i < files.length; i++)
        {
            File file = new File(dir, files[i]);
            if (file.isDirectory())
            {
                deleteDir(file);
            } else
            {
                file.delete();
            }
        }
        dir.delete();

    }

    /**
     * Get associated resources.
     */
    public DirContext getResources()
    {

        return this.resources;

    }

    /**
     * Set associated resources.
     */
    public void setResources(DirContext resources)
    {

        this.resources = resources;

        if (resources instanceof ProxyDirContext)
        {
            contextName = ((ProxyDirContext) resources).getContextName();
        }
    }


    // ------------------------------------------------------------- Properties

    /**
     * Return the context name for this class loader.
     */
    public String getContextName()
    {

        return (this.contextName);

    }

    /**
     * Return the "delegate first" flag for this class loader.
     */
    public boolean getDelegate()
    {

        return (this.delegate);

    }

    /**
     * Set the "delegate first" flag for this class loader.
     *
     * @param delegate The new "delegate first" flag
     */
    public void setDelegate(boolean delegate)
    {

        this.delegate = delegate;

    }

    /**
     * @return Returns the antiJARLocking.
     */
    public boolean getAntiJARLocking()
    {
        return antiJARLocking;
    }

    /**
     * @param antiJARLocking The antiJARLocking to set.
     */
    public void setAntiJARLocking(boolean antiJARLocking)
    {
        this.antiJARLocking = antiJARLocking;
    }

    /**
     * @return Returns the searchExternalFirst.
     */
    public boolean getSearchExternalFirst()
    {
        return searchExternalFirst;
    }

    /**
     * @param searchExternalFirst Whether external repositories should be searched first
     */
    public void setSearchExternalFirst(boolean searchExternalFirst)
    {
        this.searchExternalFirst = searchExternalFirst;
    }

    /**
     * If there is a Java SecurityManager create a read FilePermission
     * or JndiPermission for the file directory path.
     *
     * @param path file directory path
     */
    public void addPermission(String path)
    {
        if (path == null)
        {
            return;
        }

        if (securityManager != null)
        {
            Permission permission = null;
            if (path.startsWith("jndi:") || path.startsWith("jar:jndi:"))
            {
                if (!path.endsWith("/"))
                {
                    path = path + "/";
                }
                permission = new JndiPermission(path + "*");
                addPermission(permission);
            } else
            {
                if (!path.endsWith(File.separator))
                {
                    permission = new FilePermission(path, "read");
                    addPermission(permission);
                    path = path + File.separator;
                }
                permission = new FilePermission(path + "-", "read");
                addPermission(permission);
            }
        }
    }

    /**
     * If there is a Java SecurityManager create a read FilePermission
     * or JndiPermission for URL.
     *
     * @param url URL for a file or directory on local system
     */
    public void addPermission(URL url)
    {
        if (url != null)
        {
            addPermission(url.toString());
        }
    }

    /**
     * If there is a Java SecurityManager create a Permission.
     *
     * @param permission The permission
     */
    public void addPermission(Permission permission)
    {
        if ((securityManager != null) && (permission != null))
        {
            permissionList.add(permission);
        }
    }

    /**
     * Return the JAR path.
     */
    public String getJarPath()
    {

        return this.jarPath;

    }

    /**
     * Change the Jar path.
     */
    public void setJarPath(String jarPath)
    {

        this.jarPath = jarPath;

    }

    /**
     * Change the work directory.
     */
    public void setWorkDir(File workDir)
    {
        this.loaderDir = new File(workDir, "loader");
        if (loaderDir == null)
        {
            canonicalLoaderDir = null;
        } else
        {
            try
            {
                canonicalLoaderDir = loaderDir.getCanonicalPath();
                if (!canonicalLoaderDir.endsWith(File.separator))
                {
                    canonicalLoaderDir += File.separator;
                }
            }
            catch (IOException ioe)
            {
                canonicalLoaderDir = null;
            }
        }
    }

    /**
     * Utility method for use in subclasses.
     * Must be called before Lifecycle methods to have any effect.
     */
    protected void setParentClassLoader(ClassLoader pcl)
    {
        parent = pcl;
    }

    /**
     * Return the clearReferencesStopThreads flag for this Context.
     */
    public boolean getClearReferencesStopThreads()
    {
        return (this.clearReferencesStopThreads);
    }

    /**
     * Set the clearReferencesStopThreads feature for this Context.
     *
     * @param clearReferencesStopThreads The new flag value
     */
    public void setClearReferencesStopThreads(
            boolean clearReferencesStopThreads)
    {
        this.clearReferencesStopThreads = clearReferencesStopThreads;
    }

    /**
     * Return the clearReferencesStopTimerThreads flag for this Context.
     */
    public boolean getClearReferencesStopTimerThreads()
    {
        return (this.clearReferencesStopTimerThreads);
    }

    /**
     * Set the clearReferencesStopTimerThreads feature for this Context.
     *
     * @param clearReferencesStopTimerThreads The new flag value
     */
    public void setClearReferencesStopTimerThreads(
            boolean clearReferencesStopTimerThreads)
    {
        this.clearReferencesStopTimerThreads = clearReferencesStopTimerThreads;
    }

    /**
     * Return the clearReferencesLogFactoryRelease flag for this Context.
     */
    public boolean getClearReferencesLogFactoryRelease()
    {
        return (this.clearReferencesLogFactoryRelease);
    }

    /**
     * Set the clearReferencesLogFactoryRelease feature for this Context.
     *
     * @param clearReferencesLogFactoryRelease The new flag value
     */
    public void setClearReferencesLogFactoryRelease(
            boolean clearReferencesLogFactoryRelease)
    {
        this.clearReferencesLogFactoryRelease =
                clearReferencesLogFactoryRelease;
    }

    /**
     * Return the clearReferencesThreadLocals flag for this Context.
     */
    public boolean getClearReferencesThreadLocals()
    {
        return (this.clearReferencesThreadLocals);
    }

    /**
     * Set the clearReferencesThreadLocals feature for this Context.
     *
     * @param clearReferencesThreadLocals The new flag value
     */
    public void setClearReferencesThreadLocals(
            boolean clearReferencesThreadLocals)
    {
        this.clearReferencesThreadLocals = clearReferencesThreadLocals;
    }

    /**
     * Return the clearReferencesHttpClientKeepAliveThread flag for this
     * Context.
     */
    public boolean getClearReferencesHttpClientKeepAliveThread()
    {
        return (this.clearReferencesHttpClientKeepAliveThread);
    }

    /**
     * Set the clearReferencesHttpClientKeepAliveThread feature for this
     * Context.
     *
     * @param clearReferencesHttpClientKeepAliveThread The new flag value
     */
    public void setClearReferencesHttpClientKeepAliveThread(
            boolean clearReferencesHttpClientKeepAliveThread)
    {
        this.clearReferencesHttpClientKeepAliveThread =
                clearReferencesHttpClientKeepAliveThread;
    }

    /**
     * Add a new repository to the set of places this ClassLoader can look for
     * classes to be loaded.
     *
     * @param repository Name of a source of classes to be loaded, such as a
     *                   directory pathname, a JAR file pathname, or a ZIP file pathname
     * @throws IllegalArgumentException if the specified repository is
     *                                  invalid or does not exist
     */
    public void addRepository(String repository)
    {

        // Ignore any of the standard repositories, as they are set up using
        // either addJar or addRepository
        if (repository.startsWith("/WEB-INF/lib")
                || repository.startsWith("/WEB-INF/classes"))
            return;

        // Add this repository to our underlying class loader
        try
        {
            URL url = new URL(repository);
            super.addURL(url);
            hasExternalRepositories = true;
            repositoryURLs = null;
        }
        catch (MalformedURLException e)
        {
            IllegalArgumentException iae = new IllegalArgumentException
                    ("Invalid repository: " + repository);
            iae.initCause(e);
            throw iae;
        }

    }

    /**
     * Add a new repository to the set of places this ClassLoader can look for
     * classes to be loaded.
     *
     * @param repository Name of a source of classes to be loaded, such as a
     *                   directory pathname, a JAR file pathname, or a ZIP file pathname
     * @throws IllegalArgumentException if the specified repository is
     *                                  invalid or does not exist
     */
    synchronized void addRepository(String repository, File file)
    {

        // Note : There should be only one (of course), but I think we should
        // keep this a bit generic

        if (repository == null)
            return;

        if (log.isDebugEnabled())
            log.debug("addRepository(" + repository + ")");

        int i;

        // Add this repository to our internal list
        String[] result = new String[repositories.length + 1];
        for (i = 0; i < repositories.length; i++)
        {
            result[i] = repositories[i];
        }
        result[repositories.length] = repository;
        repositories = result;

        // Add the file to the list
        File[] result2 = new File[files.length + 1];
        for (i = 0; i < files.length; i++)
        {
            result2[i] = files[i];
        }
        result2[files.length] = file;
        files = result2;

    }


    // ------------------------------------------------------- Reloader Methods

    synchronized void addJar(String jar, JarFile jarFile, File file)
            throws IOException
    {

        if (jar == null)
            return;
        if (jarFile == null)
            return;
        if (file == null)
            return;

        if (log.isDebugEnabled())
            log.debug("addJar(" + jar + ")");

        int i;

        if ((jarPath != null) && (jar.startsWith(jarPath)))
        {

            String jarName = jar.substring(jarPath.length());
            while (jarName.startsWith("/"))
                jarName = jarName.substring(1);

            String[] result = new String[jarNames.length + 1];
            for (i = 0; i < jarNames.length; i++)
            {
                result[i] = jarNames[i];
            }
            result[jarNames.length] = jarName;
            jarNames = result;

        }

        try
        {

            // Register the JAR for tracking

            long lastModified =
                    ((ResourceAttributes) resources.getAttributes(jar))
                            .getLastModified();

            String[] result = new String[paths.length + 1];
            for (i = 0; i < paths.length; i++)
            {
                result[i] = paths[i];
            }
            result[paths.length] = jar;
            paths = result;

            long[] result3 = new long[lastModifiedDates.length + 1];
            for (i = 0; i < lastModifiedDates.length; i++)
            {
                result3[i] = lastModifiedDates[i];
            }
            result3[lastModifiedDates.length] = lastModified;
            lastModifiedDates = result3;

        }
        catch (NamingException e)
        {
            // Ignore
        }

        // If the JAR currently contains invalid classes, don't actually use it
        // for classloading
        if (!validateJarFile(file))
            return;

        JarFile[] result2 = new JarFile[jarFiles.length + 1];
        for (i = 0; i < jarFiles.length; i++)
        {
            result2[i] = jarFiles[i];
        }
        result2[jarFiles.length] = jarFile;
        jarFiles = result2;

        // Add the file to the list
        File[] result4 = new File[jarRealFiles.length + 1];
        for (i = 0; i < jarRealFiles.length; i++)
        {
            result4[i] = jarRealFiles[i];
        }
        result4[jarRealFiles.length] = file;
        jarRealFiles = result4;
    }

    /**
     * Return a String array of the current repositories for this class
     * loader.  If there are no repositories, a zero-length array is
     * returned.For security reason, returns a clone of the Array (since
     * String are immutable).
     */
    public String[] findRepositories()
    {

        return ((String[]) repositories.clone());

    }

    /**
     * Have one or more classes or resources been modified so that a reload
     * is appropriate?
     */
    public boolean modified()
    {

        if (log.isDebugEnabled())
            log.debug("modified()");

        // Checking for modified loaded resources
        int length = paths.length;

        // A rare race condition can occur in the updates of the two arrays
        // It's totally ok if the latest class added is not checked (it will
        // be checked the next time
        int length2 = lastModifiedDates.length;
        if (length > length2)
            length = length2;

        for (int i = 0; i < length; i++)
        {
            try
            {
                long lastModified =
                        ((ResourceAttributes) resources.getAttributes(paths[i]))
                                .getLastModified();
                if (lastModified != lastModifiedDates[i])
                {
                    if (log.isDebugEnabled())
                        log.debug("  Resource '" + paths[i]
                                + "' was modified; Date is now: "
                                + new java.util.Date(lastModified) + " Was: "
                                + new java.util.Date(lastModifiedDates[i]));
                    return (true);
                }
            }
            catch (NamingException e)
            {
                log.error("    Resource '" + paths[i] + "' is missing");
                return (true);
            }
        }

        length = jarNames.length;

        // Check if JARs have been added or removed
        if (getJarPath() != null)
        {

            try
            {
                NamingEnumeration enumeration = resources.listBindings(getJarPath());
                int i = 0;
                while (enumeration.hasMoreElements() && (i < length))
                {
                    NameClassPair ncPair = (NameClassPair) enumeration.nextElement();
                    String name = ncPair.getName();
                    // Ignore non JARs present in the lib folder
                    if (!name.endsWith(".jar"))
                        continue;
                    if (!name.equals(jarNames[i]))
                    {
                        // Missing JAR
                        log.info("    Additional JARs have been added : '"
                                + name + "'");
                        return (true);
                    }
                    i++;
                }
                if (enumeration.hasMoreElements())
                {
                    while (enumeration.hasMoreElements())
                    {
                        NameClassPair ncPair =
                                (NameClassPair) enumeration.nextElement();
                        String name = ncPair.getName();
                        // Additional non-JAR files are allowed
                        if (name.endsWith(".jar"))
                        {
                            // There was more JARs
                            log.info("    Additional JARs have been added");
                            return (true);
                        }
                    }
                } else if (i < jarNames.length)
                {
                    // There was less JARs
                    log.info("    Additional JARs have been added");
                    return (true);
                }
            }
            catch (NamingException e)
            {
                if (log.isDebugEnabled())
                    log.debug("    Failed tracking modifications of '"
                            + getJarPath() + "'");
            }
            catch (ClassCastException e)
            {
                log.error("    Failed tracking modifications of '"
                        + getJarPath() + "' : " + e.getMessage());
            }

        }

        // No classes have been modified
        return (false);

    }

    /**
     * Render a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("WebappClassLoader\r\n");
        sb.append("  context: ");
        sb.append(contextName);
        sb.append("\r\n");
        sb.append("  delegate: ");
        sb.append(delegate);
        sb.append("\r\n");
        sb.append("  repositories:\r\n");
        if (repositories != null)
        {
            for (int i = 0; i < repositories.length; i++)
            {
                sb.append("    ");
                sb.append(repositories[i]);
                sb.append("\r\n");
            }
        }
        if (this.parent != null)
        {
            sb.append("----------> Parent Classloader:\r\n");
            sb.append(this.parent.toString());
            sb.append("\r\n");
        }
        return (sb.toString());

    }

    /**
     * Add the specified URL to the classloader.
     */
    protected void addURL(URL url)
    {
        super.addURL(url);
        hasExternalRepositories = true;
        repositoryURLs = null;
    }

    /**
     * Find the specified class in our local repositories, if possible.  If
     * not found, throw <code>ClassNotFoundException</code>.
     *
     * @param name Name of the class to be loaded
     * @throws ClassNotFoundException if the class was not found
     */
    public Class findClass(String name) throws ClassNotFoundException
    {

        if (log.isDebugEnabled())
            log.debug("    findClass(" + name + ")");

        // Cannot load anything from local repositories if class loader is stopped
        if (!started)
        {
            throw new ClassNotFoundException(name);
        }

        // (1) Permission to define this class when using a SecurityManager
        if (securityManager != null)
        {
            int i = name.lastIndexOf('.');
            if (i >= 0)
            {
                try
                {
                    if (log.isTraceEnabled())
                        log.trace("      securityManager.checkPackageDefinition");
                    securityManager.checkPackageDefinition(name.substring(0, i));
                }
                catch (Exception se)
                {
                    if (log.isTraceEnabled())
                        log.trace("      -->Exception-->ClassNotFoundException", se);
                    throw new ClassNotFoundException(name, se);
                }
            }
        }

        // Ask our superclass to locate this class, if possible
        // (throws ClassNotFoundException if it is not found)
        Class clazz = null;
        try
        {
            if (log.isTraceEnabled())
                log.trace("      findClassInternal(" + name + ")");
            if (hasExternalRepositories && searchExternalFirst)
            {
                try
                {
                    clazz = super.findClass(name);
                }
                catch (ClassNotFoundException cnfe)
                {
                    // Ignore - will search internal repositories next
                }
                catch (AccessControlException ace)
                {
                    log.warn("WebappClassLoader.findClassInternal(" + name
                            + ") security exception: " + ace.getMessage(), ace);
                    throw new ClassNotFoundException(name, ace);
                }
                catch (RuntimeException e)
                {
                    if (log.isTraceEnabled())
                        log.trace("      -->RuntimeException Rethrown", e);
                    throw e;
                }
            }
            if ((clazz == null))
            {
                try
                {
                    clazz = findClassInternal(name);
                }
                catch (ClassNotFoundException cnfe)
                {
                    if (!hasExternalRepositories || searchExternalFirst)
                    {
                        throw cnfe;
                    }
                }
                catch (AccessControlException ace)
                {
                    log.warn("WebappClassLoader.findClassInternal(" + name
                            + ") security exception: " + ace.getMessage(), ace);
                    throw new ClassNotFoundException(name, ace);
                }
                catch (RuntimeException e)
                {
                    if (log.isTraceEnabled())
                        log.trace("      -->RuntimeException Rethrown", e);
                    throw e;
                }
            }
            if ((clazz == null) && hasExternalRepositories && !searchExternalFirst)
            {
                try
                {
                    clazz = super.findClass(name);
                }
                catch (AccessControlException ace)
                {
                    log.warn("WebappClassLoader.findClassInternal(" + name
                            + ") security exception: " + ace.getMessage(), ace);
                    throw new ClassNotFoundException(name, ace);
                }
                catch (RuntimeException e)
                {
                    if (log.isTraceEnabled())
                        log.trace("      -->RuntimeException Rethrown", e);
                    throw e;
                }
            }
            if (clazz == null)
            {
                if (log.isDebugEnabled())
                    log.debug("    --> Returning ClassNotFoundException");
                throw new ClassNotFoundException(name);
            }
        }
        catch (ClassNotFoundException e)
        {
            if (log.isTraceEnabled())
                log.trace("    --> Passing on ClassNotFoundException");
            throw e;
        }

        // Return the class we have located
        if (log.isTraceEnabled())
            log.debug("      Returning class " + clazz);

        if ((log.isTraceEnabled()) && (clazz != null))
        {
            ClassLoader cl;
            if (Globals.IS_SECURITY_ENABLED)
            {
                cl = AccessController.doPrivileged(
                        new PrivilegedGetClassLoader(clazz));
            } else
            {
                cl = clazz.getClassLoader();
            }
            log.debug("      Loaded by " + cl.toString());
        }
        return (clazz);

    }


    // ---------------------------------------------------- ClassLoader Methods

    /**
     * Find the specified resource in our local repository, and return a
     * <code>URL</code> refering to it, or <code>null</code> if this resource
     * cannot be found.
     *
     * @param name Name of the resource to be found
     */
    public URL findResource(final String name)
    {

        if (log.isDebugEnabled())
            log.debug("    findResource(" + name + ")");

        URL url = null;

        if (hasExternalRepositories && searchExternalFirst)
            url = super.findResource(name);

        if (url == null)
        {
            ResourceEntry entry = (ResourceEntry) resourceEntries.get(name);
            if (entry == null)
            {
                if (securityManager != null)
                {
                    PrivilegedAction<ResourceEntry> dp =
                            new PrivilegedFindResourceByName(name, name);
                    entry = AccessController.doPrivileged(dp);
                } else
                {
                    entry = findResourceInternal(name, name);
                }
            }
            if (entry != null)
            {
                url = entry.source;
            }
        }

        if ((url == null) && hasExternalRepositories && !searchExternalFirst)
            url = super.findResource(name);

        if (log.isDebugEnabled())
        {
            if (url != null)
                log.debug("    --> Returning '" + url.toString() + "'");
            else
                log.debug("    --> Resource not found, returning null");
        }
        return (url);

    }

    /**
     * Return an enumeration of <code>URLs</code> representing all of the
     * resources with the given name.  If no resources with this name are
     * found, return an empty enumeration.
     *
     * @param name Name of the resources to be found
     * @throws IOException if an input/output error occurs
     */
    public Enumeration findResources(String name) throws IOException
    {

        if (log.isDebugEnabled())
            log.debug("    findResources(" + name + ")");

        Vector result = new Vector();

        int jarFilesLength = jarFiles.length;
        int repositoriesLength = repositories.length;

        int i;

        // Adding the results of a call to the superclass
        if (hasExternalRepositories && searchExternalFirst)
        {

            Enumeration<URL> otherResourcePaths = super.findResources(name);

            while (otherResourcePaths.hasMoreElements())
            {
                result.addElement(otherResourcePaths.nextElement());
            }

        }
        // Looking at the repositories
        for (i = 0; i < repositoriesLength; i++)
        {
            try
            {
                String fullPath = repositories[i] + name;
                resources.lookup(fullPath);
                // Note : Not getting an exception here means the resource was
                // found
                try
                {
                    result.addElement(getURI(new File(files[i], name)));
                }
                catch (MalformedURLException e)
                {
                    // Ignore
                }
            }
            catch (NamingException e)
            {
            }
        }

        // Looking at the JAR files
        synchronized (jarFiles)
        {
            if (openJARs())
            {
                for (i = 0; i < jarFilesLength; i++)
                {
                    JarEntry jarEntry = jarFiles[i].getJarEntry(name);
                    if (jarEntry != null)
                    {
                        try
                        {
                            String jarFakeUrl = getURI(jarRealFiles[i]).toString();
                            jarFakeUrl = "jar:" + jarFakeUrl + "!/" + name;
                            result.addElement(new URL(jarFakeUrl));
                        }
                        catch (MalformedURLException e)
                        {
                            // Ignore
                        }
                    }
                }
            }
        }

        // Adding the results of a call to the superclass
        if (hasExternalRepositories && !searchExternalFirst)
        {

            Enumeration otherResourcePaths = super.findResources(name);

            while (otherResourcePaths.hasMoreElements())
            {
                result.addElement(otherResourcePaths.nextElement());
            }

        }

        return result.elements();

    }

    /**
     * Find the resource with the given name.  A resource is some data
     * (images, audio, text, etc.) that can be accessed by class code in a
     * way that is independent of the location of the code.  The name of a
     * resource is a "/"-separated path name that identifies the resource.
     * If the resource cannot be found, return <code>null</code>.
     * <p/>
     * This method searches according to the following algorithm, returning
     * as soon as it finds the appropriate URL.  If the resource cannot be
     * found, returns <code>null</code>.
     * <ul>
     * <li>If the <code>delegate</code> property is set to <code>true</code>,
     * call the <code>getResource()</code> method of the parent class
     * loader, if any.</li>
     * <li>Call <code>findResource()</code> to find this resource in our
     * locally defined repositories.</li>
     * <li>Call the <code>getResource()</code> method of the parent class
     * loader, if any.</li>
     * </ul>
     *
     * @param name Name of the resource to return a URL for
     */
    public URL getResource(String name)
    {

        if (log.isDebugEnabled())
            log.debug("getResource(" + name + ")");
        URL url = null;

        // (1) Delegate to parent if requested
        if (delegate)
        {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader " + parent);
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            url = loader.getResource(name);
            if (url != null)
            {
                if (log.isDebugEnabled())
                    log.debug("  --> Returning '" + url.toString() + "'");
                return (url);
            }
        }

        // (2) Search local repositories
        url = findResource(name);
        if (url != null)
        {
            // Locating the repository for special handling in the case 
            // of a JAR
            if (antiJARLocking)
            {
                ResourceEntry entry = (ResourceEntry) resourceEntries.get(name);
                try
                {
                    String repository = entry.codeBase.toString();
                    if ((repository.endsWith(".jar"))
                            && (!(name.endsWith(".class"))))
                    {
                        // Copy binary content to the work directory if not present
                        File resourceFile = new File(loaderDir, name);
                        url = getURI(resourceFile);
                    }
                }
                catch (Exception e)
                {
                    // Ignore
                }
            }
            if (log.isDebugEnabled())
                log.debug("  --> Returning '" + url.toString() + "'");
            return (url);
        }

        // (3) Delegate to parent unconditionally if not already attempted
        if (!delegate)
        {
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            url = loader.getResource(name);
            if (url != null)
            {
                if (log.isDebugEnabled())
                    log.debug("  --> Returning '" + url.toString() + "'");
                return (url);
            }
        }

        // (4) Resource was not found
        if (log.isDebugEnabled())
            log.debug("  --> Resource not found, returning null");
        return (null);

    }

    /**
     * Find the resource with the given name, and return an input stream
     * that can be used for reading it.  The search order is as described
     * for <code>getResource()</code>, after checking to see if the resource
     * data has been previously cached.  If the resource cannot be found,
     * return <code>null</code>.
     *
     * @param name Name of the resource to return an input stream for
     */
    public InputStream getResourceAsStream(String name)
    {

        if (log.isDebugEnabled())
            log.debug("getResourceAsStream(" + name + ")");
        InputStream stream = null;

        // (0) Check for a cached copy of this resource
        stream = findLoadedResource(name);
        if (stream != null)
        {
            if (log.isDebugEnabled())
                log.debug("  --> Returning stream from cache");
            return (stream);
        }

        // (1) Delegate to parent if requested
        if (delegate)
        {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader " + parent);
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            stream = loader.getResourceAsStream(name);
            if (stream != null)
            {
                // FIXME - cache???
                if (log.isDebugEnabled())
                    log.debug("  --> Returning stream from parent");
                return (stream);
            }
        }

        // (2) Search local repositories
        if (log.isDebugEnabled())
            log.debug("  Searching local repositories");
        URL url = findResource(name);
        if (url != null)
        {
            // FIXME - cache???
            if (log.isDebugEnabled())
                log.debug("  --> Returning stream from local");
            stream = findLoadedResource(name);
            try
            {
                if (hasExternalRepositories && (stream == null))
                    stream = url.openStream();
            }
            catch (IOException e)
            {
                ; // Ignore
            }
            if (stream != null)
                return (stream);
        }

        // (3) Delegate to parent unconditionally
        if (!delegate)
        {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader unconditionally " + parent);
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            stream = loader.getResourceAsStream(name);
            if (stream != null)
            {
                // FIXME - cache???
                if (log.isDebugEnabled())
                    log.debug("  --> Returning stream from parent");
                return (stream);
            }
        }

        // (4) Resource was not found
        if (log.isDebugEnabled())
            log.debug("  --> Resource not found, returning null");
        return (null);

    }

    /**
     * Load the class with the specified name.  This method searches for
     * classes in the same manner as <code>loadClass(String, boolean)</code>
     * with <code>false</code> as the second argument.
     *
     * @param name Name of the class to be loaded
     * @throws ClassNotFoundException if the class was not found
     */
    public Class loadClass(String name) throws ClassNotFoundException
    {

        return (loadClass(name, false));

    }

    /**
     * Load the class with the specified name, searching using the following
     * algorithm until it finds and returns the class.  If the class cannot
     * be found, returns <code>ClassNotFoundException</code>.
     * <ul>
     * <li>Call <code>findLoadedClass(String)</code> to check if the
     * class has already been loaded.  If it has, the same
     * <code>Class</code> object is returned.</li>
     * <li>If the <code>delegate</code> property is set to <code>true</code>,
     * call the <code>loadClass()</code> method of the parent class
     * loader, if any.</li>
     * <li>Call <code>findClass()</code> to find this class in our locally
     * defined repositories.</li>
     * <li>Call the <code>loadClass()</code> method of our parent
     * class loader, if any.</li>
     * </ul>
     * If the class was found using the above steps, and the
     * <code>resolve</code> flag is <code>true</code>, this method will then
     * call <code>resolveClass(Class)</code> on the resulting Class object.
     *
     * @param name    Name of the class to be loaded
     * @param resolve If <code>true</code> then resolve the class
     * @throws ClassNotFoundException if the class was not found
     */
    public synchronized Class loadClass(String name, boolean resolve)
            throws ClassNotFoundException
    {

        if (log.isDebugEnabled())
            log.debug("loadClass(" + name + ", " + resolve + ")");
        Class clazz = null;

        // Log access to stopped classloader
        if (!started)
        {
            try
            {
                throw new IllegalStateException();
            }
            catch (IllegalStateException e)
            {
                log.info(sm.getString("webappClassLoader.stopped", name), e);
            }
        }

        // (0) Check our previously loaded local class cache
        clazz = findLoadedClass0(name);
        if (clazz != null)
        {
            if (log.isDebugEnabled())
                log.debug("  Returning class from cache");
            if (resolve)
                resolveClass(clazz);
            return (clazz);
        }

        // (0.1) Check our previously loaded class cache
        clazz = findLoadedClass(name);
        if (clazz != null)
        {
            if (log.isDebugEnabled())
                log.debug("  Returning class from cache");
            if (resolve)
                resolveClass(clazz);
            return (clazz);
        }

        // (0.2) Try loading the class with the system class loader, to prevent
        //       the webapp from overriding J2SE classes
        try
        {
            clazz = system.loadClass(name);
            if (clazz != null)
            {
                if (resolve)
                    resolveClass(clazz);
                return (clazz);
            }
        }
        catch (ClassNotFoundException e)
        {
            // Ignore
        }

        // (0.5) Permission to access this class when using a SecurityManager
        if (securityManager != null)
        {
            int i = name.lastIndexOf('.');
            if (i >= 0)
            {
                try
                {
                    securityManager.checkPackageAccess(name.substring(0, i));
                }
                catch (SecurityException se)
                {
                    String error = "Security Violation, attempt to use " +
                            "Restricted Class: " + name;
                    log.info(error, se);
                    throw new ClassNotFoundException(error, se);
                }
            }
        }

        boolean delegateLoad = delegate || filter(name);

        // (1) Delegate to our parent if requested
        if (delegateLoad)
        {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader1 " + parent);
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            try
            {
                clazz = loader.loadClass(name);
                if (clazz != null)
                {
                    if (log.isDebugEnabled())
                        log.debug("  Loading class from parent");
                    if (resolve)
                        resolveClass(clazz);
                    return (clazz);
                }
            }
            catch (ClassNotFoundException e)
            {
                ;
            }
        }

        // (2) Search local repositories
        if (log.isDebugEnabled())
            log.debug("  Searching local repositories");
        try
        {
            clazz = findClass(name);
            if (clazz != null)
            {
                if (log.isDebugEnabled())
                    log.debug("  Loading class from local repository");
                if (resolve)
                    resolveClass(clazz);
                return (clazz);
            }
        }
        catch (ClassNotFoundException e)
        {
            ;
        }

        // (3) Delegate to parent unconditionally
        if (!delegateLoad)
        {
            if (log.isDebugEnabled())
                log.debug("  Delegating to parent classloader at end: " + parent);
            ClassLoader loader = parent;
            if (loader == null)
                loader = system;
            try
            {
                clazz = loader.loadClass(name);
                if (clazz != null)
                {
                    if (log.isDebugEnabled())
                        log.debug("  Loading class from parent");
                    if (resolve)
                        resolveClass(clazz);
                    return (clazz);
                }
            }
            catch (ClassNotFoundException e)
            {
                ;
            }
        }

        throw new ClassNotFoundException(name);

    }

    /**
     * Get the Permissions for a CodeSource.  If this instance
     * of WebappClassLoader is for a web application context,
     * add read FilePermission or JndiPermissions for the base
     * directory (if unpacked),
     * the context URL, and jar file resources.
     *
     * @param codeSource where the code was loaded from
     * @return PermissionCollection for CodeSource
     */
    protected PermissionCollection getPermissions(CodeSource codeSource)
    {

        String codeUrl = codeSource.getLocation().toString();
        PermissionCollection pc;
        if ((pc = (PermissionCollection) loaderPC.get(codeUrl)) == null)
        {
            pc = super.getPermissions(codeSource);
            if (pc != null)
            {
                Iterator perms = permissionList.iterator();
                while (perms.hasNext())
                {
                    Permission p = (Permission) perms.next();
                    pc.add(p);
                }
                loaderPC.put(codeUrl, pc);
            }
        }
        return (pc);

    }

    /**
     * Returns the search path of URLs for loading classes and resources.
     * This includes the original list of URLs specified to the constructor,
     * along with any URLs subsequently appended by the addURL() method.
     *
     * @return the search path of URLs for loading classes and resources.
     */
    public URL[] getURLs()
    {

        if (repositoryURLs != null)
        {
            return repositoryURLs.clone();
        }

        URL[] external = super.getURLs();

        int filesLength = files.length;
        int jarFilesLength = jarRealFiles.length;
        int externalsLength = external.length;
        int off = 0;
        int i;

        try
        {

            URL[] urls = new URL[filesLength + jarFilesLength + externalsLength];
            if (searchExternalFirst)
            {
                for (i = 0; i < externalsLength; i++)
                {
                    urls[i] = external[i];
                }
                off = externalsLength;
            }
            for (i = 0; i < filesLength; i++)
            {
                urls[off + i] = getURL(files[i], true);
            }
            off += filesLength;
            for (i = 0; i < jarFilesLength; i++)
            {
                urls[off + i] = getURL(jarRealFiles[i], true);
            }
            off += jarFilesLength;
            if (!searchExternalFirst)
            {
                for (i = 0; i < externalsLength; i++)
                {
                    urls[off + i] = external[i];
                }
            }

            repositoryURLs = urls;

        }
        catch (MalformedURLException e)
        {
            repositoryURLs = new URL[0];
        }

        return repositoryURLs.clone();

    }

    /**
     * Add a lifecycle event listener to this component.
     *
     * @param listener The listener to add
     */
    public void addLifecycleListener(LifecycleListener listener)
    {
    }

    /**
     * Get the lifecycle listeners associated with this lifecycle. If this
     * Lifecycle has no listeners registered, a zero-length array is returned.
     */
    public LifecycleListener[] findLifecycleListeners()
    {
        return new LifecycleListener[0];
    }


    // ------------------------------------------------------ Lifecycle Methods

    /**
     * Remove a lifecycle event listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removeLifecycleListener(LifecycleListener listener)
    {
    }

    /**
     * Start the class loader.
     *
     * @throws LifecycleException if a lifecycle error occurs
     */
    public void start() throws LifecycleException
    {

        started = true;
        String encoding = null;
        try
        {
            encoding = System.getProperty("file.encoding");
        }
        catch (Exception e)
        {
            return;
        }
        if (encoding.indexOf("EBCDIC") != -1)
        {
            needConvert = true;
        }

    }

    public boolean isStarted()
    {
        return started;
    }

    /**
     * Stop the class loader.
     *
     * @throws LifecycleException if a lifecycle error occurs
     */
    public void stop() throws LifecycleException
    {

        // Clearing references should be done before setting started to
        // false, due to possible side effects
        clearReferences();

        started = false;

        int length = files.length;
        for (int i = 0; i < length; i++)
        {
            files[i] = null;
        }

        length = jarFiles.length;
        for (int i = 0; i < length; i++)
        {
            try
            {
                if (jarFiles[i] != null)
                {
                    jarFiles[i].close();
                }
            }
            catch (IOException e)
            {
                // Ignore
            }
            jarFiles[i] = null;
        }

        notFoundResources.clear();
        resourceEntries.clear();
        resources = null;
        repositories = null;
        repositoryURLs = null;
        files = null;
        jarFiles = null;
        jarRealFiles = null;
        jarPath = null;
        jarNames = null;
        lastModifiedDates = null;
        paths = null;
        hasExternalRepositories = false;
        parent = null;

        permissionList.clear();
        loaderPC.clear();

        if (loaderDir != null)
        {
            deleteDir(loaderDir);
        }

    }

    /**
     * Used to periodically signal to the classloader to release
     * JAR resources.
     */
    public void closeJARs(boolean force)
    {
        if (jarFiles.length > 0)
        {
            synchronized (jarFiles)
            {
                if (force || (System.currentTimeMillis()
                        > (lastJarAccessed + 90000)))
                {
                    for (int i = 0; i < jarFiles.length; i++)
                    {
                        try
                        {
                            if (jarFiles[i] != null)
                            {
                                jarFiles[i].close();
                                jarFiles[i] = null;
                            }
                        }
                        catch (IOException e)
                        {
                            if (log.isDebugEnabled())
                            {
                                log.debug("Failed to close JAR", e);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clear references.
     */
    protected void clearReferences()
    {

        // De-register any remaining JDBC drivers
        clearReferencesJdbc();

        // Stop any threads the web application started
        clearReferencesThreads();

        // Clear any ThreadLocals loaded by this class loader
        clearReferencesThreadLocals();

        // Clear RMI Targets loaded by this class loader
        clearReferencesRmiTargets();

        // Null out any static or final fields from loaded classes,
        // as a workaround for apparent garbage collection bugs
        if (ENABLE_CLEAR_REFERENCES)
        {
            clearReferencesStaticFinal();
        }

        // Clear the IntrospectionUtils cache.
        IntrospectionUtils.clear();

        // Clear the classloader reference in common-logging
        if (clearReferencesLogFactoryRelease)
        {
            org.apache.juli.logging.LogFactory.release(this);
        }

        // Clear the resource bundle cache
        // This shouldn't be necessary, the cache uses weak references but
        // it has caused leaks. Oddly, using the leak detection code in
        // standard host allows the class loader to be GC'd. This has been seen
        // on Sun but not IBM JREs. Maybe a bug in Sun's GC impl?
        clearReferencesResourceBundles();

        // Clear the classloader reference in the VM's bean introspector
        java.beans.Introspector.flushCaches();

    }

    /**
     * Deregister any JDBC drivers registered by the webapp that the webapp
     * forgot. This is made unnecessary complex because a) DriverManager
     * checks the class loader of the calling class (it would be much easier
     * if it checked the context class loader) b) using reflection would
     * create a dependency on the DriverManager implementation which can,
     * and has, changed.
     * <p/>
     * We can't just create an instance of JdbcLeakPrevention as it will be
     * loaded by the common class loader (since it's .class file is in the
     * $CATALINA_HOME/lib directory). This would fail DriverManager's check
     * on the class loader of the calling class. So, we load the bytes via
     * our parent class loader but define the class with this class loader
     * so the JdbcLeakPrevention looks like a webapp class to the
     * DriverManager.
     * <p/>
     * If only apps cleaned up after themselves...
     */
    private final void clearReferencesJdbc()
    {
        InputStream is = getResourceAsStream(
                "org/apache/catalina/loader/JdbcLeakPrevention.class");
        // We know roughly how big the class will be (~ 1K) so allow 2k as a
        // starting point
        byte[] classBytes = new byte[2048];
        int offset = 0;
        try
        {
            int read = is.read(classBytes, offset, classBytes.length - offset);
            while (read > -1)
            {
                offset += read;
                if (offset == classBytes.length)
                {
                    // Buffer full - double size
                    byte[] tmp = new byte[classBytes.length * 2];
                    System.arraycopy(classBytes, 0, tmp, 0, classBytes.length);
                    classBytes = tmp;
                }
                read = is.read(classBytes, offset, classBytes.length - offset);
            }
            Class<?> lpClass =
                    defineClass("org.apache.catalina.loader.JdbcLeakPrevention",
                            classBytes, 0, offset, this.getClass().getProtectionDomain());
            Object obj = lpClass.newInstance();
            @SuppressWarnings("unchecked")
            List<String> driverNames = (List<String>) obj.getClass().getMethod(
                    "clearJdbcDriverRegistrations").invoke(obj);
            for (String name : driverNames)
            {
                log.error(sm.getString("webappClassLoader.clearJbdc",
                        contextName, name));
            }
        }
        catch (Exception e)
        {
            // So many things to go wrong above...
            log.warn(sm.getString(
                    "webappClassLoader.jdbcRemoveFailed", contextName), e);
        }
        finally
        {
            if (is != null)
            {
                try
                {
                    is.close();
                }
                catch (IOException ioe)
                {
                    log.warn(sm.getString(
                            "webappClassLoader.jdbcRemoveStreamError",
                            contextName), ioe);
                }
            }
        }
    }


    // ------------------------------------------------------ Protected Methods

    private final void clearReferencesStaticFinal()
    {

        @SuppressWarnings("unchecked")
        Collection<ResourceEntry> values =
                ((HashMap<String, ResourceEntry>) resourceEntries.clone()).values();
        Iterator<ResourceEntry> loadedClasses = values.iterator();
        //
        // walk through all loaded class to trigger initialization for
        //    any uninitialized classes, otherwise initialization of
        //    one class may call a previously cleared class.
        while (loadedClasses.hasNext())
        {
            ResourceEntry entry = loadedClasses.next();
            if (entry.loadedClass != null)
            {
                Class<?> clazz = entry.loadedClass;
                try
                {
                    Field[] fields = clazz.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++)
                    {
                        if (Modifier.isStatic(fields[i].getModifiers()))
                        {
                            fields[i].get(null);
                            break;
                        }
                    }
                }
                catch (Throwable t)
                {
                    // Ignore
                }
            }
        }
        loadedClasses = values.iterator();
        while (loadedClasses.hasNext())
        {
            ResourceEntry entry = loadedClasses.next();
            if (entry.loadedClass != null)
            {
                Class<?> clazz = entry.loadedClass;
                try
                {
                    Field[] fields = clazz.getDeclaredFields();
                    for (int i = 0; i < fields.length; i++)
                    {
                        Field field = fields[i];
                        int mods = field.getModifiers();
                        if (field.getType().isPrimitive()
                                || (field.getName().indexOf("$") != -1))
                        {
                            continue;
                        }
                        if (Modifier.isStatic(mods))
                        {
                            try
                            {
                                field.setAccessible(true);
                                if (Modifier.isFinal(mods))
                                {
                                    if (!((field.getType().getName().startsWith("java."))
                                            || (field.getType().getName().startsWith("javax."))))
                                    {
                                        nullInstance(field.get(null));
                                    }
                                } else
                                {
                                    field.set(null, null);
                                    if (log.isDebugEnabled())
                                    {
                                        log.debug("Set field " + field.getName()
                                                + " to null in class " + clazz.getName());
                                    }
                                }
                            }
                            catch (Throwable t)
                            {
                                if (log.isDebugEnabled())
                                {
                                    log.debug("Could not set field " + field.getName()
                                            + " to null in class " + clazz.getName(), t);
                                }
                            }
                        }
                    }
                }
                catch (Throwable t)
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug("Could not clean fields for class " + clazz.getName(), t);
                    }
                }
            }
        }

    }

    private void nullInstance(Object instance)
    {
        if (instance == null)
        {
            return;
        }
        Field[] fields = instance.getClass().getDeclaredFields();
        for (int i = 0; i < fields.length; i++)
        {
            Field field = fields[i];
            int mods = field.getModifiers();
            if (field.getType().isPrimitive()
                    || (field.getName().indexOf("$") != -1))
            {
                continue;
            }
            try
            {
                field.setAccessible(true);
                if (Modifier.isStatic(mods) && Modifier.isFinal(mods))
                {
                    // Doing something recursively is too risky
                    continue;
                }
                Object value = field.get(instance);
                if (null != value)
                {
                    Class<? extends Object> valueClass = value.getClass();
                    if (!loadedByThisOrChild(valueClass))
                    {
                        if (log.isDebugEnabled())
                        {
                            log.debug("Not setting field " + field.getName() +
                                    " to null in object of class " +
                                    instance.getClass().getName() +
                                    " because the referenced object was of type " +
                                    valueClass.getName() +
                                    " which was not loaded by this WebappClassLoader.");
                        }
                    } else
                    {
                        field.set(instance, null);
                        if (log.isDebugEnabled())
                        {
                            log.debug("Set field " + field.getName()
                                    + " to null in class " + instance.getClass().getName());
                        }
                    }
                }
            }
            catch (Throwable t)
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Could not set field " + field.getName()
                            + " to null in object instance of class "
                            + instance.getClass().getName(), t);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void clearReferencesThreads()
    {
        Thread[] threads = getThreads();

        // Iterate over the set of threads
        for (Thread thread : threads)
        {
            if (thread != null)
            {
                ClassLoader ccl = thread.getContextClassLoader();
                if (ccl == this)
                {
                    // Don't warn about this thread
                    if (thread == Thread.currentThread())
                    {
                        continue;
                    }

                    // JVM controlled threads
                    ThreadGroup tg = thread.getThreadGroup();
                    if (tg != null &&
                            JVM_THREAD_GROUP_NAMES.contains(tg.getName()))
                    {

                        // HttpClient keep-alive threads
                        if (clearReferencesHttpClientKeepAliveThread &&
                                thread.getName().equals("Keep-Alive-Timer"))
                        {
                            thread.setContextClassLoader(parent);
                            log.debug(sm.getString(
                                    "webappClassLoader.checkThreadsHttpClient"));
                        }

                        // Don't warn about remaining JVM controlled threads
                        continue;
                    }

                    // Skip threads that have already died
                    if (!thread.isAlive())
                    {
                        continue;
                    }

                    // TimerThread can be stopped safely so treat separately
                    // "java.util.TimerThread" in Sun/Oracle JDK
                    // "java.util.Timer$TimerImpl" in Apache Harmony and in IBM JDK
                    if (thread.getClass().getName().startsWith("java.util.Timer") &&
                            clearReferencesStopTimerThreads)
                    {
                        clearReferencesStopTimerThread(thread);
                        continue;
                    }

                    if (isRequestThread(thread))
                    {
                        log.error(sm.getString("webappClassLoader.warnRequestThread",
                                contextName, thread.getName()));
                    } else
                    {
                        log.error(sm.getString("webappClassLoader.warnThread",
                                contextName, thread.getName()));
                    }

                    // Don't try an stop the threads unless explicitly
                    // configured to do so
                    if (!clearReferencesStopThreads)
                    {
                        continue;
                    }

                    // If the thread has been started via an executor, try
                    // shutting down the executor
                    try
                    {
                        // Runnable wrapped by Thread
                        // "target" in Sun/Oracle JDK
                        // "runnable" in IBM JDK
                        // "action" in Apache Harmony
                        Object target = null;
                        for (String fieldName : new String[]{"target",
                                "runnable", "action"})
                        {
                            try
                            {
                                Field targetField = thread.getClass()
                                        .getDeclaredField(fieldName);
                                targetField.setAccessible(true);
                                target = targetField.get(thread);
                                break;
                            }
                            catch (NoSuchFieldException nfe)
                            {
                                continue;
                            }
                        }

                        // "java.util.concurrent" code is in public domain,
                        // so all implementations are similar
                        if (target != null &&
                                target.getClass().getCanonicalName() != null
                                && target.getClass().getCanonicalName().equals(
                                "java.util.concurrent.ThreadPoolExecutor.Worker"))
                        {
                            Field executorField =
                                    target.getClass().getDeclaredField("this$0");
                            executorField.setAccessible(true);
                            Object executor = executorField.get(target);
                            if (executor instanceof ThreadPoolExecutor)
                            {
                                ((ThreadPoolExecutor) executor).shutdownNow();
                            }
                        }
                    }
                    catch (SecurityException e)
                    {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), contextName), e);
                    }
                    catch (NoSuchFieldException e)
                    {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), contextName), e);
                    }
                    catch (IllegalArgumentException e)
                    {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), contextName), e);
                    }
                    catch (IllegalAccessException e)
                    {
                        log.warn(sm.getString(
                                "webappClassLoader.stopThreadFail",
                                thread.getName(), contextName), e);
                    }

                    // This method is deprecated and for good reason. This is
                    // very risky code but is the only option at this point.
                    // A *very* good reason for apps to do this clean-up
                    // themselves.
                    thread.stop();
                }
            }
        }
    }

    /*
     * Look at a threads stack trace to see if it is a request thread or not. It
     * isn't perfect, but it should be good-enough for most cases.
     */
    private boolean isRequestThread(Thread thread)
    {

        StackTraceElement[] elements = thread.getStackTrace();

        if (elements == null || elements.length == 0)
        {
            // Must have stopped already. Too late to ignore it. Assume not a
            // request processing thread.
            return false;
        }

        // Step through the methods in reverse order looking for calls to any
        // CoyoteAdapter method. All request threads will have this unless
        // Tomcat has been heavily modified - in which case there isn't much we
        // can do.
        for (int i = 0; i < elements.length; i++)
        {
            StackTraceElement element = elements[elements.length - (i + 1)];
            if ("org.apache.catalina.connector.CoyoteAdapter".equals(
                    element.getClassName()))
            {
                return true;
            }
        }
        return false;
    }

    private void clearReferencesStopTimerThread(Thread thread)
    {

        // Need to get references to:
        // in Sun/Oracle JDK:
        // - newTasksMayBeScheduled field (in java.util.TimerThread)
        // - queue field
        // - queue.clear()
        // in IBM JDK, Apache Harmony:
        // - cancel() method (in java.util.Timer$TimerImpl)

        try
        {

            try
            {
                Field newTasksMayBeScheduledField =
                        thread.getClass().getDeclaredField("newTasksMayBeScheduled");
                newTasksMayBeScheduledField.setAccessible(true);
                Field queueField = thread.getClass().getDeclaredField("queue");
                queueField.setAccessible(true);

                Object queue = queueField.get(thread);

                Method clearMethod = queue.getClass().getDeclaredMethod("clear");
                clearMethod.setAccessible(true);

                synchronized (queue)
                {
                    newTasksMayBeScheduledField.setBoolean(thread, false);
                    clearMethod.invoke(queue);
                    queue.notify();  // In case queue was already empty.
                }

            }
            catch (NoSuchFieldException nfe)
            {
                Method cancelMethod = thread.getClass().getDeclaredMethod("cancel");
                synchronized (thread)
                {
                    cancelMethod.setAccessible(true);
                    cancelMethod.invoke(thread);
                }
            }

            log.error(sm.getString("webappClassLoader.warnTimerThread",
                    contextName, thread.getName()));

        }
        catch (IllegalAccessException e)
        {
            log.warn(sm.getString(
                    "webappClassLoader.stopTimerThreadFail",
                    thread.getName(), contextName), e);
        }
        catch (NoSuchMethodException e)
        {
            log.warn(sm.getString(
                    "webappClassLoader.stopTimerThreadFail",
                    thread.getName(), contextName), e);
        }
        catch (InvocationTargetException e)
        {
            log.warn(sm.getString(
                    "webappClassLoader.stopTimerThreadFail",
                    thread.getName(), contextName), e);
        }
    }

    private void clearReferencesThreadLocals()
    {
        Thread[] threads = getThreads();

        try
        {
            // Make the fields in the Thread class that store ThreadLocals
            // accessible
            Field threadLocalsField =
                    Thread.class.getDeclaredField("threadLocals");
            threadLocalsField.setAccessible(true);
            Field inheritableThreadLocalsField =
                    Thread.class.getDeclaredField("inheritableThreadLocals");
            inheritableThreadLocalsField.setAccessible(true);
            // Make the underlying array of ThreadLoad.ThreadLocalMap.Entry objects
            // accessible
            Class<?> tlmClass =
                    Class.forName("java.lang.ThreadLocal$ThreadLocalMap");
            Field tableField = tlmClass.getDeclaredField("table");
            tableField.setAccessible(true);
            Method expungeStaleEntriesMethod = tlmClass.getDeclaredMethod("expungeStaleEntries");
            expungeStaleEntriesMethod.setAccessible(true);

            for (int i = 0; i < threads.length; i++)
            {
                Object threadLocalMap;
                if (threads[i] != null)
                {

                    // Clear the first map
                    threadLocalMap = threadLocalsField.get(threads[i]);
                    if (null != threadLocalMap)
                    {
                        expungeStaleEntriesMethod.invoke(threadLocalMap);
                        checkThreadLocalMapForLeaks(threadLocalMap, tableField);
                    }

                    // Clear the second map
                    threadLocalMap =
                            inheritableThreadLocalsField.get(threads[i]);
                    if (null != threadLocalMap)
                    {
                        expungeStaleEntriesMethod.invoke(threadLocalMap);
                        checkThreadLocalMapForLeaks(threadLocalMap, tableField);
                    }
                }
            }
        }
        catch (SecurityException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (NoSuchFieldException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (ClassNotFoundException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (IllegalArgumentException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (IllegalAccessException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (NoSuchMethodException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
        catch (InvocationTargetException e)
        {
            log.warn(sm.getString("webappClassLoader.clearThreadLocalFail",
                    contextName), e);
        }
    }

    /*
     * Clears the given thread local map object. Also pass in the field that
     * points to the internal table to save re-calculating it on every
     * call to this method.
     */
    private void checkThreadLocalMapForLeaks(Object map, Field internalTableField)
            throws NoSuchMethodException, IllegalAccessException,
            NoSuchFieldException, InvocationTargetException
    {
        if (map != null)
        {
            Method mapRemove =
                    map.getClass().getDeclaredMethod("remove",
                            ThreadLocal.class);
            mapRemove.setAccessible(true);
            Object[] table = (Object[]) internalTableField.get(map);
            int staleEntriesCount = 0;
            if (table != null)
            {
                for (int j = 0; j < table.length; j++)
                {
                    if (table[j] != null)
                    {
                        boolean remove = false;
                        // Check the key
                        Object key = ((Reference<?>) table[j]).get();
                        if (this.equals(key) ||
                                isLoadedByThisWebappClassLoader(key))
                        {
                            remove = true;
                        }
                        // Check the value
                        Field valueField =
                                table[j].getClass().getDeclaredField("value");
                        valueField.setAccessible(true);
                        Object value = valueField.get(table[j]);
                        if (this.equals(value) ||
                                isLoadedByThisWebappClassLoader(value))
                        {
                            remove = true;
                        }
                        if (remove)
                        {
                            Object[] args = new Object[5];
                            args[0] = contextName;
                            if (key != null)
                            {
                                args[1] = getPrettyClassName(key.getClass());
                                try
                                {
                                    args[2] = key.toString();
                                }
                                catch (Exception e)
                                {
                                    log.error(sm.getString(
                                            "webappClassLoader.clearThreadLocal.badKey",
                                            args[1]), e);
                                    args[2] = sm.getString(
                                            "webappClassLoader.clearThreadLocal.unknown");
                                }
                            }
                            if (value != null)
                            {
                                args[3] = getPrettyClassName(value.getClass());
                                try
                                {
                                    args[4] = value.toString();
                                }
                                catch (Exception e)
                                {
                                    log.error(sm.getString(
                                            "webappClassLoader.clearThreadLocal.badValue",
                                            args[3]), e);
                                    args[4] = sm.getString(
                                            "webappClassLoader.clearThreadLocal.unknown");
                                }
                            }
                            if (value == null)
                            {
                                if (log.isDebugEnabled())
                                {
                                    log.debug(sm.getString(
                                            "webappClassLoader.clearThreadLocalDebug",
                                            args));
                                    if (clearReferencesThreadLocals)
                                    {
                                        log.debug(sm.getString(
                                                "webappClassLoader.clearThreadLocalDebugClear"));
                                    }
                                }
                            } else
                            {
                                log.error(sm.getString(
                                        "webappClassLoader.clearThreadLocal",
                                        args));
                                if (clearReferencesThreadLocals)
                                {
                                    log.info(sm.getString(
                                            "webappClassLoader.clearThreadLocalClear"));
                                }
                            }
                            if (clearReferencesThreadLocals)
                            {
                                if (key == null)
                                {
                                    staleEntriesCount++;
                                } else
                                {
                                    mapRemove.invoke(map, key);
                                }
                            }
                        }
                    }
                }
            }
            if (staleEntriesCount > 0)
            {
                Method mapRemoveStale =
                        map.getClass().getDeclaredMethod("expungeStaleEntries");
                mapRemoveStale.setAccessible(true);
                mapRemoveStale.invoke(map);
            }
        }
    }

    private String getPrettyClassName(Class clazz)
    {
        String name = clazz.getCanonicalName();
        if (name == null)
        {
            name = clazz.getName();
        }
        return name;
    }

    /**
     * @param o object to test
     * @return true if o has been loaded by the current classloader or one of its descendants.
     */
    private boolean isLoadedByThisWebappClassLoader(Object o)
    {
        if (o == null)
        {
            return false;
        }
        for (ClassLoader loader = o.getClass().getClassLoader(); loader != null; loader = loader.getParent())
        {
            if (loader == this)
            {
                return true;
            }
        }
        return false;
    }

    /*
     * Get the set of current threads as an array.
     */
    private Thread[] getThreads()
    {
        // Get the current thread group 
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        // Find the root thread group
        while (tg.getParent() != null)
        {
            tg = tg.getParent();
        }

        int threadCountGuess = tg.activeCount() + 50;
        Thread[] threads = new Thread[threadCountGuess];
        int threadCountActual = tg.enumerate(threads);
        // Make sure we don't miss any threads
        while (threadCountActual == threadCountGuess)
        {
            threadCountGuess *= 2;
            threads = new Thread[threadCountGuess];
            // Note tg.enumerate(Thread[]) silently ignores any threads that
            // can't fit into the array 
            threadCountActual = tg.enumerate(threads);
        }

        return threads;
    }

    /**
     * This depends on the internals of the Sun JVM so it does everything by
     * reflection.
     */
    private void clearReferencesRmiTargets()
    {
        try
        {
            // Need access to the ccl field of sun.rmi.transport.Target
            Class<?> objectTargetClass =
                    Class.forName("sun.rmi.transport.Target");
            Field cclField = objectTargetClass.getDeclaredField("ccl");
            cclField.setAccessible(true);

            // Clear the objTable map
            Class<?> objectTableClass =
                    Class.forName("sun.rmi.transport.ObjectTable");
            Field objTableField = objectTableClass.getDeclaredField("objTable");
            objTableField.setAccessible(true);
            Object objTable = objTableField.get(null);
            if (objTable == null)
            {
                return;
            }

            // Iterate over the values in the table
            if (objTable instanceof Map<?, ?>)
            {
                Iterator<?> iter = ((Map<?, ?>) objTable).values().iterator();
                while (iter.hasNext())
                {
                    Object obj = iter.next();
                    Object cclObject = cclField.get(obj);
                    if (this == cclObject)
                    {
                        iter.remove();
                    }
                }
            }

            // Clear the implTable map
            Field implTableField = objectTableClass.getDeclaredField("implTable");
            implTableField.setAccessible(true);
            Object implTable = implTableField.get(null);
            if (implTable == null)
            {
                return;
            }

            // Iterate over the values in the table
            if (implTable instanceof Map<?, ?>)
            {
                Iterator<?> iter = ((Map<?, ?>) implTable).values().iterator();
                while (iter.hasNext())
                {
                    Object obj = iter.next();
                    Object cclObject = cclField.get(obj);
                    if (this == cclObject)
                    {
                        iter.remove();
                    }
                }
            }
        }
        catch (ClassNotFoundException e)
        {
            log.info(sm.getString("webappClassLoader.clearRmiInfo",
                    contextName), e);
        }
        catch (SecurityException e)
        {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    contextName), e);
        }
        catch (NoSuchFieldException e)
        {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    contextName), e);
        }
        catch (IllegalArgumentException e)
        {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    contextName), e);
        }
        catch (IllegalAccessException e)
        {
            log.warn(sm.getString("webappClassLoader.clearRmiFail",
                    contextName), e);
        }
    }

    /**
     * Clear the {@link ResourceBundle} cache of any bundles loaded by this
     * class loader or any class loader where this loader is a parent class
     * loader. Whilst {@link ResourceBundle#clearCache()} could be used there
     * are complications around the {@link JasperLoader} that mean a reflection
     * based approach is more likely to be complete.
     * <p/>
     * The ResourceBundle is using WeakReferences so it shouldn't be pinning the
     * class loader in memory. However, it is. Therefore clear ou the
     * references.
     */
    private void clearReferencesResourceBundles()
    {
        // Get a reference to the cache
        try
        {
            Field cacheListField =
                    ResourceBundle.class.getDeclaredField("cacheList");
            cacheListField.setAccessible(true);

            // Java 6 uses ConcurrentMap
            // Java 5 uses SoftCache extends Abstract Map
            // So use Map and it *should* work with both
            Map<?, ?> cacheList = (Map<?, ?>) cacheListField.get(null);

            // Get the keys (loader references are in the key)
            Set<?> keys = cacheList.keySet();

            Field loaderRefField = null;

            // Iterate over the keys looking at the loader instances
            Iterator<?> keysIter = keys.iterator();

            int countRemoved = 0;

            while (keysIter.hasNext())
            {
                Object key = keysIter.next();

                if (loaderRefField == null)
                {
                    loaderRefField =
                            key.getClass().getDeclaredField("loaderRef");
                    loaderRefField.setAccessible(true);
                }
                WeakReference<?> loaderRef =
                        (WeakReference<?>) loaderRefField.get(key);

                ClassLoader loader = (ClassLoader) loaderRef.get();

                while (loader != null && loader != this)
                {
                    loader = loader.getParent();
                }

                if (loader != null)
                {
                    keysIter.remove();
                    countRemoved++;
                }
            }

            if (countRemoved > 0 && log.isDebugEnabled())
            {
                log.debug(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesCount",
                        Integer.valueOf(countRemoved), contextName));
            }
        }
        catch (SecurityException e)
        {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    contextName), e);
        }
        catch (NoSuchFieldException e)
        {
            if (System.getProperty("java.vendor").startsWith("Sun"))
            {
                log.error(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesFail",
                        contextName), e);
            } else
            {
                log.debug(sm.getString(
                        "webappClassLoader.clearReferencesResourceBundlesFail",
                        contextName), e);
            }
        }
        catch (IllegalArgumentException e)
        {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    contextName), e);
        }
        catch (IllegalAccessException e)
        {
            log.error(sm.getString(
                    "webappClassLoader.clearReferencesResourceBundlesFail",
                    contextName), e);
        }
    }

    /**
     * Determine whether a class was loaded by this class loader or one of
     * its child class loaders.
     */
    protected boolean loadedByThisOrChild(Class clazz)
    {
        boolean result = false;
        for (ClassLoader classLoader = clazz.getClassLoader();
             null != classLoader; classLoader = classLoader.getParent())
        {
            if (classLoader.equals(this))
            {
                result = true;
                break;
            }
        }
        return result;
    }

    /**
     * Used to periodically signal to the classloader to release JAR resources.
     */
    protected boolean openJARs()
    {
        if (started && (jarFiles.length > 0))
        {
            lastJarAccessed = System.currentTimeMillis();
            if (jarFiles[0] == null)
            {
                for (int i = 0; i < jarFiles.length; i++)
                {
                    try
                    {
                        jarFiles[i] = new JarFile(jarRealFiles[i]);
                    }
                    catch (IOException e)
                    {
                        if (log.isDebugEnabled())
                        {
                            log.debug("Failed to open JAR", e);
                        }
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Find specified class in local repositories.
     *
     * @return the loaded class, or null if the class isn't found
     */
    protected Class findClassInternal(String name)
            throws ClassNotFoundException
    {

        if (!validate(name))
            throw new ClassNotFoundException(name);

        String tempPath = name.replace('.', '/');
        String classPath = tempPath + ".class";

        ResourceEntry entry = null;

        if (securityManager != null)
        {
            PrivilegedAction<ResourceEntry> dp =
                    new PrivilegedFindResourceByName(name, classPath);
            entry = AccessController.doPrivileged(dp);
        } else
        {
            entry = findResourceInternal(name, classPath);
        }

        if (entry == null)
            throw new ClassNotFoundException(name);

        Class clazz = entry.loadedClass;
        if (clazz != null)
            return clazz;

        synchronized (this)
        {
            clazz = entry.loadedClass;
            if (clazz != null)
                return clazz;

            if (entry.binaryContent == null)
                throw new ClassNotFoundException(name);

            // Looking up the package
            String packageName = null;
            int pos = name.lastIndexOf('.');
            if (pos != -1)
                packageName = name.substring(0, pos);

            Package pkg = null;

            if (packageName != null)
            {
                pkg = getPackage(packageName);
                // Define the package (if null)
                if (pkg == null)
                {
                    try
                    {
                        if (entry.manifest == null)
                        {
                            definePackage(packageName, null, null, null, null,
                                    null, null, null);
                        } else
                        {
                            definePackage(packageName, entry.manifest,
                                    entry.codeBase);
                        }
                    }
                    catch (IllegalArgumentException e)
                    {
                        // Ignore: normal error due to dual definition of package
                    }
                    pkg = getPackage(packageName);
                }
            }

            if (securityManager != null)
            {

                // Checking sealing
                if (pkg != null)
                {
                    boolean sealCheck = true;
                    if (pkg.isSealed())
                    {
                        sealCheck = pkg.isSealed(entry.codeBase);
                    } else
                    {
                        sealCheck = (entry.manifest == null)
                                || !isPackageSealed(packageName, entry.manifest);
                    }
                    if (!sealCheck)
                        throw new SecurityException
                                ("Sealing violation loading " + name + " : Package "
                                        + packageName + " is sealed.");
                }

            }

            try
            {
                clazz = defineClass(name, entry.binaryContent, 0,
                        entry.binaryContent.length,
                        new CodeSource(entry.codeBase, entry.certificates));
            }
            catch (UnsupportedClassVersionError ucve)
            {
                throw new UnsupportedClassVersionError(
                        ucve.getLocalizedMessage() + " " +
                                sm.getString("webappClassLoader.wrongVersion",
                                        name));
            }
            entry.loadedClass = clazz;
            entry.binaryContent = null;
            entry.source = null;
            entry.codeBase = null;
            entry.manifest = null;
            entry.certificates = null;
        }

        return clazz;

    }

    /**
     * Find specified resource in local repositories.
     *
     * @return the loaded resource, or null if the resource isn't found
     */
    protected ResourceEntry findResourceInternal(File file, String path)
    {
        ResourceEntry entry = new ResourceEntry();
        try
        {
            entry.source = getURI(new File(file, path));
            entry.codeBase = getURL(new File(file, path), false);
        }
        catch (MalformedURLException e)
        {
            return null;
        }
        return entry;
    }

    /**
     * Find specified resource in local repositories.
     *
     * @return the loaded resource, or null if the resource isn't found
     */
    protected ResourceEntry findResourceInternal(String name, String path)
    {

        if (!started)
        {
            log.info(sm.getString("webappClassLoader.stopped", name));
            return null;
        }

        if ((name == null) || (path == null))
            return null;

        ResourceEntry entry = (ResourceEntry) resourceEntries.get(name);
        if (entry != null)
            return entry;

        int contentLength = -1;
        InputStream binaryStream = null;

        int jarFilesLength = jarFiles.length;
        int repositoriesLength = repositories.length;

        int i;

        Resource resource = null;

        boolean fileNeedConvert = false;

        for (i = 0; (entry == null) && (i < repositoriesLength); i++)
        {
            try
            {

                String fullPath = repositories[i] + path;

                Object lookupResult = resources.lookup(fullPath);
                if (lookupResult instanceof Resource)
                {
                    resource = (Resource) lookupResult;
                }

                // Note : Not getting an exception here means the resource was
                // found
                entry = findResourceInternal(files[i], path);

                ResourceAttributes attributes =
                        (ResourceAttributes) resources.getAttributes(fullPath);
                contentLength = (int) attributes.getContentLength();
                entry.lastModified = attributes.getLastModified();

                if (resource != null)
                {


                    try
                    {
                        binaryStream = resource.streamContent();
                    }
                    catch (IOException e)
                    {
                        return null;
                    }

                    if (needConvert)
                    {
                        if (path.endsWith(".properties"))
                        {
                            fileNeedConvert = true;
                        }
                    }

                    // Register the full path for modification checking
                    // Note: Only syncing on a 'constant' object is needed
                    synchronized (allPermission)
                    {

                        int j;

                        long[] result2 =
                                new long[lastModifiedDates.length + 1];
                        for (j = 0; j < lastModifiedDates.length; j++)
                        {
                            result2[j] = lastModifiedDates[j];
                        }
                        result2[lastModifiedDates.length] = entry.lastModified;
                        lastModifiedDates = result2;

                        String[] result = new String[paths.length + 1];
                        for (j = 0; j < paths.length; j++)
                        {
                            result[j] = paths[j];
                        }
                        result[paths.length] = fullPath;
                        paths = result;

                    }

                }

            }
            catch (NamingException e)
            {
            }
        }

        if ((entry == null) && (notFoundResources.containsKey(name)))
            return null;

        JarEntry jarEntry = null;

        synchronized (jarFiles)
        {

            try
            {
                if (!openJARs())
                {
                    return null;
                }
                for (i = 0; (entry == null) && (i < jarFilesLength); i++)
                {

                    jarEntry = jarFiles[i].getJarEntry(path);

                    if (jarEntry != null)
                    {

                        entry = new ResourceEntry();
                        try
                        {
                            entry.codeBase = getURL(jarRealFiles[i], false);
                            String jarFakeUrl = getURI(jarRealFiles[i]).toString();
                            jarFakeUrl = "jar:" + jarFakeUrl + "!/" + path;
                            entry.source = new URL(jarFakeUrl);
                            entry.lastModified = jarRealFiles[i].lastModified();
                        }
                        catch (MalformedURLException e)
                        {
                            return null;
                        }
                        contentLength = (int) jarEntry.getSize();
                        try
                        {
                            entry.manifest = jarFiles[i].getManifest();
                            binaryStream = jarFiles[i].getInputStream(jarEntry);
                        }
                        catch (IOException e)
                        {
                            return null;
                        }

                        // Extract resources contained in JAR to the workdir
                        if (antiJARLocking && !(path.endsWith(".class")))
                        {
                            byte[] buf = new byte[1024];
                            File resourceFile = new File
                                    (loaderDir, jarEntry.getName());
                            if (!resourceFile.exists())
                            {
                                Enumeration<JarEntry> entries =
                                        jarFiles[i].entries();
                                while (entries.hasMoreElements())
                                {
                                    JarEntry jarEntry2 = entries.nextElement();
                                    if (!(jarEntry2.isDirectory())
                                            && (!jarEntry2.getName().endsWith
                                            (".class")))
                                    {
                                        resourceFile = new File
                                                (loaderDir, jarEntry2.getName());
                                        try
                                        {
                                            if (!resourceFile.getCanonicalPath().startsWith(
                                                    canonicalLoaderDir))
                                            {
                                                throw new IllegalArgumentException(
                                                        sm.getString("webappClassLoader.illegalJarPath",
                                                                jarEntry2.getName()));
                                            }
                                        }
                                        catch (IOException ioe)
                                        {
                                            throw new IllegalArgumentException(
                                                    sm.getString("webappClassLoader.validationErrorJarPath",
                                                            jarEntry2.getName()), ioe);
                                        }
                                        resourceFile.getParentFile().mkdirs();
                                        FileOutputStream os = null;
                                        InputStream is = null;
                                        try
                                        {
                                            is = jarFiles[i].getInputStream
                                                    (jarEntry2);
                                            os = new FileOutputStream
                                                    (resourceFile);
                                            while (true)
                                            {
                                                int n = is.read(buf);
                                                if (n <= 0)
                                                {
                                                    break;
                                                }
                                                os.write(buf, 0, n);
                                            }
                                        }
                                        catch (IOException e)
                                        {
                                            // Ignore
                                        }
                                        finally
                                        {
                                            try
                                            {
                                                if (is != null)
                                                {
                                                    is.close();
                                                }
                                            }
                                            catch (IOException e)
                                            {
                                            }
                                            try
                                            {
                                                if (os != null)
                                                {
                                                    os.close();
                                                }
                                            }
                                            catch (IOException e)
                                            {
                                            }
                                        }
                                    }
                                }
                            }
                        }

                    }

                }

                if (entry == null)
                {
                    synchronized (notFoundResources)
                    {
                        notFoundResources.put(name, name);
                    }
                    return null;
                }

                if (binaryStream != null)
                {

                    byte[] binaryContent = new byte[contentLength];

                    int pos = 0;
                    try
                    {

                        while (true)
                        {
                            int n = binaryStream.read(binaryContent, pos,
                                    binaryContent.length - pos);
                            if (n <= 0)
                                break;
                            pos += n;
                        }
                    }
                    catch (IOException e)
                    {
                        log.error(sm.getString("webappClassLoader.readError", name), e);
                        return null;
                    }
                    if (fileNeedConvert)
                    {
                        // Workaround for certain files on platforms that use
                        // EBCDIC encoding, when they are read through FileInputStream.
                        // See commit message of rev.303915 for details
                        // http://svn.apache.org/viewvc?view=revision&revision=303915
                        String str = new String(binaryContent, 0, pos);
                        try
                        {
                            binaryContent = str.getBytes("UTF-8");
                        }
                        catch (Exception e)
                        {
                            return null;
                        }
                    }
                    entry.binaryContent = binaryContent;

                    // The certificates are only available after the JarEntry 
                    // associated input stream has been fully read
                    if (jarEntry != null)
                    {
                        entry.certificates = jarEntry.getCertificates();
                    }

                }
            }
            finally
            {
                if (binaryStream != null)
                {
                    try
                    {
                        binaryStream.close();
                    }
                    catch (IOException e)
                    { /* Ignore */}
                }
            }
        }

        // Add the entry in the local resource repository
        synchronized (resourceEntries)
        {
            // Ensures that all the threads which may be in a race to load
            // a particular class all end up with the same ResourceEntry
            // instance
            ResourceEntry entry2 = (ResourceEntry) resourceEntries.get(name);
            if (entry2 == null)
            {
                resourceEntries.put(name, entry);
            } else
            {
                entry = entry2;
            }
        }

        return entry;

    }

    /**
     * Returns true if the specified package name is sealed according to the
     * given manifest.
     */
    protected boolean isPackageSealed(String name, Manifest man)
    {

        String path = name.replace('.', '/') + '/';
        Attributes attr = man.getAttributes(path);
        String sealed = null;
        if (attr != null)
        {
            sealed = attr.getValue(Name.SEALED);
        }
        if (sealed == null)
        {
            if ((attr = man.getMainAttributes()) != null)
            {
                sealed = attr.getValue(Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);

    }

    /**
     * Finds the resource with the given name if it has previously been
     * loaded and cached by this class loader, and return an input stream
     * to the resource data.  If this resource has not been cached, return
     * <code>null</code>.
     *
     * @param name Name of the resource to return
     */
    protected InputStream findLoadedResource(String name)
    {

        ResourceEntry entry = (ResourceEntry) resourceEntries.get(name);
        if (entry != null)
        {
            if (entry.binaryContent != null)
                return new ByteArrayInputStream(entry.binaryContent);
        }
        return (null);

    }

    /**
     * Finds the class with the given name if it has previously been
     * loaded and cached by this class loader, and return the Class object.
     * If this class has not been cached, return <code>null</code>.
     *
     * @param name Name of the resource to return
     */
    protected Class findLoadedClass0(String name)
    {

        ResourceEntry entry = (ResourceEntry) resourceEntries.get(name);
        if (entry != null)
        {
            return entry.loadedClass;
        }
        return (null);  // FIXME - findLoadedResource()

    }

    /**
     * Refresh the system policy file, to pick up eventual changes.
     */
    protected void refreshPolicy()
    {

        try
        {
            // The policy file may have been modified to adjust 
            // permissions, so we're reloading it when loading or 
            // reloading a Context
            Policy policy = Policy.getPolicy();
            policy.refresh();
        }
        catch (AccessControlException e)
        {
            // Some policy files may restrict this, even for the core,
            // so this exception is ignored
        }

    }

    /**
     * Filter classes.
     *
     * @param name class name
     * @return true if the class should be filtered
     */
    protected boolean filter(String name)
    {

        if (name == null)
            return false;

        // Looking up the package
        String packageName = null;
        int pos = name.lastIndexOf('.');
        if (pos != -1)
            packageName = name.substring(0, pos);
        else
            return false;

        for (int i = 0; i < packageTriggers.length; i++)
        {
            if (packageName.startsWith(packageTriggers[i]))
                return true;
        }

        return false;

    }

    /**
     * Validate a classname. As per SRV.9.7.2, we must restict loading of
     * classes from J2SE (java.*) and classes of the servlet API
     * (javax.servlet.*). That should enhance robustness and prevent a number
     * of user error (where an older version of servlet.jar would be present
     * in /WEB-INF/lib).
     *
     * @param name class name
     * @return true if the name is valid
     */
    protected boolean validate(String name)
    {

        if (name == null)
            return false;
        if (name.startsWith("java."))
            return false;

        return true;

    }

    /**
     * Check the specified JAR file, and return <code>true</code> if it does
     * not contain any of the trigger classes.
     *
     * @param file The JAR file to be checked
     * @throws IOException if an input/output error occurs
     */
    protected boolean validateJarFile(File file)
            throws IOException
    {

        if (triggers == null)
            return (true);

        JarFile jarFile = null;
        try
        {
            jarFile = new JarFile(file);
            for (int i = 0; i < triggers.length; i++)
            {
                Class<?> clazz = null;
                try
                {
                    if (parent != null)
                    {
                        clazz = parent.loadClass(triggers[i]);
                    } else
                    {
                        clazz = Class.forName(triggers[i]);
                    }
                }
                catch (Throwable t)
                {
                    clazz = null;
                }
                if (clazz == null)
                    continue;
                String name = triggers[i].replace('.', '/') + ".class";
                if (log.isDebugEnabled())
                    log.debug(" Checking for " + name);
                JarEntry jarEntry = jarFile.getJarEntry(name);
                if (jarEntry != null)
                {
                    log.info("validateJarFile(" + file +
                            ") - jar not loaded. See Servlet Spec 2.3, "
                            + "section 9.7.2. Offending class: " + name);
                    return false;
                }
            }
            return true;
        }
        finally
        {
            if (jarFile != null)
            {
                try
                {
                    jarFile.close();
                }
                catch (IOException ioe)
                {
                    // Ignore
                }
            }
        }
    }

    /**
     * Get URL.
     */
    protected URL getURL(File file, boolean encoded)
            throws MalformedURLException
    {

        File realFile = file;
        try
        {
            realFile = realFile.getCanonicalFile();
        }
        catch (IOException e)
        {
            // Ignore
        }
        if (encoded)
        {
            return getURI(realFile);
        } else
        {
            return realFile.toURL();
        }

    }

    /**
     * Get URL.
     */
    protected URL getURI(File file)
            throws MalformedURLException
    {


        File realFile = file;
        try
        {
            realFile = realFile.getCanonicalFile();
        }
        catch (IOException e)
        {
            // Ignore
        }
        return realFile.toURI().toURL();

    }

    /**
     * @deprecated Not used
     */
    protected class PrivilegedFindResource
            implements PrivilegedAction
    {

        protected File file;
        protected String path;

        PrivilegedFindResource(File file, String path)
        {
            this.file = file;
            this.path = path;
        }

        public Object run()
        {
            return findResourceInternal(file, path);
        }

    }

    protected class PrivilegedFindResourceByName
            implements PrivilegedAction<ResourceEntry>
    {

        protected String name;
        protected String path;

        PrivilegedFindResourceByName(String name, String path)
        {
            this.name = name;
            this.path = path;
        }

        public ResourceEntry run()
        {
            return findResourceInternal(name, path);
        }

    }

    protected final class PrivilegedGetClassLoader
            implements PrivilegedAction<ClassLoader>
    {

        public Class<?> clazz;

        public PrivilegedGetClassLoader(Class<?> clazz)
        {
            this.clazz = clazz;
        }

        public ClassLoader run()
        {
            return clazz.getClassLoader();
        }
    }


}
