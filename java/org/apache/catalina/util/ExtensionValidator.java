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

package org.apache.catalina.util;

import org.apache.catalina.core.StandardContext;
import org.apache.naming.resources.Resource;

import javax.naming.Binding;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;


/**
 * Ensures that all extension dependies are resolved for a WEB application
 * are met. This class builds a master list of extensions available to an
 * applicaiton and then validates those extensions.
 * <p/>
 * See http://docs.oracle.com/javase/1.4.2/docs/guide/extensions/spec.html for
 * a detailed explanation of the extension mechanism in Java.
 *
 * @author Greg Murray
 * @author Justyna Horwat
 */
public final class ExtensionValidator
{

    private static org.apache.juli.logging.Log log =
            org.apache.juli.logging.LogFactory.getLog(ExtensionValidator.class);

    /**
     * The string resources for this package.
     */
    private static StringManager sm =
            StringManager.getManager("org.apache.catalina.util");

    private static ArrayList containerAvailableExtensions = null;
    private static ArrayList containerManifestResources = new ArrayList();


    // ----------------------------------------------------- Static Initializer


    /**
     *  This static initializer loads the container level extensions that are
     *  available to all web applications. This method scans all extension 
     *  directories available via the "java.ext.dirs" System property. 
     *
     *  The System Class-Path is also scanned for jar files that may contain 
     *  available extensions.
     */
    static
    {

        // check for container level optional packages
        String systemClasspath = System.getProperty("java.class.path");

        StringTokenizer strTok = new StringTokenizer(systemClasspath,
                File.pathSeparator);

        // build a list of jar files in the classpath
        while (strTok.hasMoreTokens())
        {
            String classpathItem = strTok.nextToken();
            if (classpathItem.toLowerCase().endsWith(".jar"))
            {
                File item = new File(classpathItem);
                if (item.isFile())
                {
                    try
                    {
                        addSystemResource(item);
                    }
                    catch (IOException e)
                    {
                        log.error(sm.getString
                                ("extensionValidator.failload", item), e);
                    }
                }
            }
        }

        // add specified folders to the list
        addFolderList("java.ext.dirs");
        addFolderList("catalina.ext.dirs");

    }


    // --------------------------------------------------------- Public Methods


    /**
     * Runtime validation of a Web Applicaiton.
     * <p/>
     * This method uses JNDI to look up the resources located under a
     * <code>DirContext</code>. It locates Web Application MANIFEST.MF
     * file in the /META-INF/ directory of the application and all
     * MANIFEST.MF files in each JAR file located in the WEB-INF/lib
     * directory and creates an <code>ArrayList</code> of
     * <code>ManifestResorce<code> objects. These objects are then passed
     * to the validateManifestResources method for validation.
     *
     * @param dirContext The JNDI root of the Web Application
     * @param context    The context from which the Logger and path to the
     *                   application
     * @return true if all required extensions satisfied
     */
    public static synchronized boolean validateApplication(
            DirContext dirContext,
            StandardContext context)
            throws IOException
    {

        String appName = context.getPath();
        ArrayList appManifestResources = new ArrayList();
        // If the application context is null it does not exist and 
        // therefore is not valid
        if (dirContext == null) return false;
        // Find the Manifest for the Web Applicaiton
        InputStream inputStream = null;
        try
        {
            NamingEnumeration wne = dirContext.listBindings("/META-INF/");
            Binding binding = (Binding) wne.nextElement();
            if (binding.getName().toUpperCase().equals("MANIFEST.MF"))
            {
                Resource resource = (Resource) dirContext.lookup
                        ("/META-INF/" + binding.getName());
                inputStream = resource.streamContent();
                Manifest manifest = new Manifest(inputStream);
                inputStream.close();
                inputStream = null;
                ManifestResource mre = new ManifestResource
                        (sm.getString("extensionValidator.web-application-manifest"),
                                manifest, ManifestResource.WAR);
                appManifestResources.add(mre);
            }
        }
        catch (NamingException nex)
        {
            // Application does not contain a MANIFEST.MF file
        }
        catch (NoSuchElementException nse)
        {
            // Application does not contain a MANIFEST.MF file
        }
        finally
        {
            if (inputStream != null)
            {
                try
                {
                    inputStream.close();
                }
                catch (Throwable t)
                {
                    // Ignore
                }
            }
        }

        // Locate the Manifests for all bundled JARs
        NamingEnumeration ne = null;
        try
        {
            if (dirContext != null)
            {
                ne = dirContext.listBindings("WEB-INF/lib/");
            }
            while ((ne != null) && ne.hasMoreElements())
            {
                Binding binding = (Binding) ne.nextElement();
                if (!binding.getName().toLowerCase().endsWith(".jar"))
                {
                    continue;
                }
                Object obj =
                        dirContext.lookup("/WEB-INF/lib/" + binding.getName());
                if (!(obj instanceof Resource))
                {
                    // Probably a directory named xxx.jar - ignore it
                    continue;
                }
                Resource resource = (Resource) obj;
                Manifest jmanifest = getManifest(resource.streamContent());
                if (jmanifest != null)
                {
                    ManifestResource mre = new ManifestResource(
                            binding.getName(),
                            jmanifest,
                            ManifestResource.APPLICATION);
                    appManifestResources.add(mre);
                }
            }
        }
        catch (NamingException nex)
        {
            // Jump out of the check for this application because it 
            // has no resources
        }

        return validateManifestResources(appName, appManifestResources);
    }


    /**
     * Checks to see if the given system JAR file contains a MANIFEST, and adds
     * it to the container's manifest resources.
     *
     * @param jarFile The system JAR whose manifest to add
     */
    public static void addSystemResource(File jarFile) throws IOException
    {
        Manifest manifest = getManifest(new FileInputStream(jarFile));
        if (manifest != null)
        {
            ManifestResource mre
                    = new ManifestResource(jarFile.getAbsolutePath(),
                    manifest,
                    ManifestResource.SYSTEM);
            containerManifestResources.add(mre);
        }
    }


    // -------------------------------------------------------- Private Methods


    /**
     * Validates a <code>ArrayList</code> of <code>ManifestResource</code>
     * objects. This method requires an application name (which is the
     * context root of the application at runtime).
     * <p/>
     * <code>false</false> is returned if the extension dependencies
     * represented by any given <code>ManifestResource</code> objects
     * is not met.
     * <p/>
     * This method should also provide static validation of a Web Applicaiton
     * if provided with the necessary parameters.
     *
     * @param appName   The name of the Application that will appear in the
     *                  error messages
     * @param resources A list of <code>ManifestResource</code> objects
     *                  to be validated.
     * @return true if manifest resource file requirements are met
     */
    private static boolean validateManifestResources(String appName,
                                                     ArrayList resources)
    {
        boolean passes = true;
        int failureCount = 0;
        ArrayList availableExtensions = null;

        Iterator it = resources.iterator();
        while (it.hasNext())
        {
            ManifestResource mre = (ManifestResource) it.next();
            ArrayList requiredList = mre.getRequiredExtensions();
            if (requiredList == null)
            {
                continue;
            }

            // build the list of available extensions if necessary
            if (availableExtensions == null)
            {
                availableExtensions = buildAvailableExtensionsList(resources);
            }

            // load the container level resource map if it has not been built
            // yet
            if (containerAvailableExtensions == null)
            {
                containerAvailableExtensions
                        = buildAvailableExtensionsList(containerManifestResources);
            }

            // iterate through the list of required extensions
            Iterator rit = requiredList.iterator();
            while (rit.hasNext())
            {
                boolean found = false;
                Extension requiredExt = (Extension) rit.next();
                // check the applicaion itself for the extension
                if (availableExtensions != null)
                {
                    Iterator ait = availableExtensions.iterator();
                    while (ait.hasNext())
                    {
                        Extension targetExt = (Extension) ait.next();
                        if (targetExt.isCompatibleWith(requiredExt))
                        {
                            requiredExt.setFulfilled(true);
                            found = true;
                            break;
                        }
                    }
                }
                // check the container level list for the extension
                if (!found && containerAvailableExtensions != null)
                {
                    Iterator cit = containerAvailableExtensions.iterator();
                    while (cit.hasNext())
                    {
                        Extension targetExt = (Extension) cit.next();
                        if (targetExt.isCompatibleWith(requiredExt))
                        {
                            requiredExt.setFulfilled(true);
                            found = true;
                            break;
                        }
                    }
                }
                if (!found)
                {
                    // Failure
                    log.info(sm.getString(
                            "extensionValidator.extension-not-found-error",
                            appName, mre.getResourceName(),
                            requiredExt.getExtensionName()));
                    passes = false;
                    failureCount++;
                }
            }
        }

        if (!passes)
        {
            log.info(sm.getString(
                    "extensionValidator.extension-validation-error", appName,
                    failureCount + ""));
        }

        return passes;
    }

    /* 
     * Build this list of available extensions so that we do not have to 
     * re-build this list every time we iterate through the list of required 
     * extensions. All available extensions in all of the 
     * <code>MainfestResource</code> objects will be added to a 
     * <code>HashMap</code> which is returned on the first dependency list
     * processing pass. 
     *
     * The key is the name + implementation version.
     *
     * NOTE: A list is built only if there is a dependency that needs 
     * to be checked (performance optimization).
     *
     * @param resources A list of <code>ManifestResource</code> objects
     *
     * @return HashMap Map of available extensions
     */
    private static ArrayList buildAvailableExtensionsList(ArrayList resources)
    {

        ArrayList availableList = null;

        Iterator it = resources.iterator();
        while (it.hasNext())
        {
            ManifestResource mre = (ManifestResource) it.next();
            ArrayList list = mre.getAvailableExtensions();
            if (list != null)
            {
                Iterator values = list.iterator();
                while (values.hasNext())
                {
                    Extension ext = (Extension) values.next();
                    if (availableList == null)
                    {
                        availableList = new ArrayList();
                        availableList.add(ext);
                    } else
                    {
                        availableList.add(ext);
                    }
                }
            }
        }

        return availableList;
    }

    /**
     * Return the Manifest from a jar file or war file
     *
     * @param inStream Input stream to a WAR or JAR file
     * @return The WAR's or JAR's manifest
     */
    private static Manifest getManifest(InputStream inStream)
            throws IOException
    {

        Manifest manifest = null;
        JarInputStream jin = null;

        try
        {
            jin = new JarInputStream(inStream);
            manifest = jin.getManifest();
            jin.close();
            jin = null;
        }
        finally
        {
            if (jin != null)
            {
                try
                {
                    jin.close();
                }
                catch (Throwable t)
                {
                    // Ignore
                }
            }
        }

        return manifest;
    }


    /**
     * Add the JARs specified to the extension list.
     */
    private static void addFolderList(String property)
    {

        // get the files in the extensions directory
        String extensionsDir = System.getProperty(property);
        if (extensionsDir != null)
        {
            StringTokenizer extensionsTok
                    = new StringTokenizer(extensionsDir, File.pathSeparator);
            while (extensionsTok.hasMoreTokens())
            {
                File targetDir = new File(extensionsTok.nextToken());
                if (!targetDir.isDirectory())
                {
                    continue;
                }
                File[] files = targetDir.listFiles();
                for (int i = 0; i < files.length; i++)
                {
                    if (files[i].getName().toLowerCase().endsWith(".jar") &&
                            files[i].isFile())
                    {
                        try
                        {
                            addSystemResource(files[i]);
                        }
                        catch (IOException e)
                        {
                            log.error
                                    (sm.getString
                                            ("extensionValidator.failload", files[i]), e);
                        }
                    }
                }
            }
        }

    }


}
