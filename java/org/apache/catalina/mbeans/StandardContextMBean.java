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

package org.apache.catalina.mbeans;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.deploy.ContextEnvironment;
import org.apache.catalina.deploy.ContextResource;
import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.catalina.deploy.NamingResources;
import org.apache.tomcat.util.modeler.BaseModelMBean;
import org.apache.tomcat.util.modeler.ManagedBean;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.RuntimeOperationsException;
import java.util.ArrayList;

/**
 * <p>A <strong>ModelMBean</strong> implementation for the
 * <code>org.apache.catalina.core.StandardContext</code> component.</p>
 *
 * @author Amy Roh
 */

public class StandardContextMBean extends BaseModelMBean
{


    // ----------------------------------------------------------- Constructors


    /**
     * The configuration information registry for our managed beans.
     */
    protected Registry registry = MBeanUtils.createRegistry();


    // ----------------------------------------------------- Instance Variables
    /**
     * The <code>ManagedBean</code> information describing this MBean.
     */
    protected ManagedBean managed =
            registry.findManagedBean("StandardContext");

    /**
     * Construct a <code>ModelMBean</code> with default
     * <code>ModelMBeanInfo</code> information.
     *
     * @throws MBeanException             if the initializer of an object
     *                                    throws an exception
     * @throws RuntimeOperationsException if an IllegalArgumentException
     *                                    occurs
     */
    public StandardContextMBean()
            throws MBeanException, RuntimeOperationsException
    {

        super();

    }


    // ------------------------------------------------------------- Attributes

    /**
     * Return the naming resources associated with this web application.
     */
    private NamingResources getNamingResources()
    {

        return ((StandardContext) this.resource).getNamingResources();

    }

    /**
     * Return the naming resources associated with this web application.
     */
    public void reload()
    {

        ((StandardContext) this.resource).reload();

    }


    /**
     * Return the MBean Names of the set of defined environment entries for
     * this web application
     */
    public String[] getEnvironments()
    {
        ContextEnvironment[] envs = getNamingResources().findEnvironments();
        ArrayList results = new ArrayList();
        for (int i = 0; i < envs.length; i++)
        {
            try
            {
                ObjectName oname =
                        MBeanUtils.createObjectName(managed.getDomain(), envs[i]);
                results.add(oname.toString());
            }
            catch (MalformedObjectNameException e)
            {
                IllegalArgumentException iae = new IllegalArgumentException
                        ("Cannot create object name for environment " + envs[i]);
                iae.initCause(e);
                throw iae;
            }
        }
        return ((String[]) results.toArray(new String[results.size()]));

    }


    /**
     * Return the MBean Names of all the defined resource references for this
     * application.
     */
    public String[] getResources()
    {

        ContextResource[] resources = getNamingResources().findResources();
        ArrayList results = new ArrayList();
        for (int i = 0; i < resources.length; i++)
        {
            try
            {
                ObjectName oname =
                        MBeanUtils.createObjectName(managed.getDomain(), resources[i]);
                results.add(oname.toString());
            }
            catch (MalformedObjectNameException e)
            {
                IllegalArgumentException iae = new IllegalArgumentException
                        ("Cannot create object name for resource " + resources[i]);
                iae.initCause(e);
                throw iae;
            }
        }
        return ((String[]) results.toArray(new String[results.size()]));

    }


    /**
     * Return the MBean Names of all the defined resource links for this
     * application
     */
    public String[] getResourceLinks()
    {

        ContextResourceLink[] links = getNamingResources().findResourceLinks();
        ArrayList results = new ArrayList();
        for (int i = 0; i < links.length; i++)
        {
            try
            {
                ObjectName oname =
                        MBeanUtils.createObjectName(managed.getDomain(), links[i]);
                results.add(oname.toString());
            }
            catch (MalformedObjectNameException e)
            {
                IllegalArgumentException iae = new IllegalArgumentException
                        ("Cannot create object name for resource " + links[i]);
                iae.initCause(e);
                throw iae;
            }
        }
        return ((String[]) results.toArray(new String[results.size()]));

    }


    /**
     * Return the naming resources associated with this web application.
     */
    public javax.naming.directory.DirContext getStaticResources()
    {

        return ((StandardContext) this.resource).getResources();

    }


    /**
     * Return the naming resources associated with this web application.
     */
    public String[] getWelcomeFiles()
    {

        return ((StandardContext) this.resource).findWelcomeFiles();

    }


    // ------------------------------------------------------------- Operations


    /**
     * Add an environment entry for this web application.
     *
     * @param envName New environment entry name
     */
    public String addEnvironment(String envName, String type)
            throws MalformedObjectNameException
    {

        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return null;
        }
        ContextEnvironment env = nresources.findEnvironment(envName);
        if (env != null)
        {
            throw new IllegalArgumentException
                    ("Invalid environment name - already exists '" + envName + "'");
        }
        env = new ContextEnvironment();
        env.setName(envName);
        env.setType(type);
        nresources.addEnvironment(env);

        // Return the corresponding MBean name
        ManagedBean managed = registry.findManagedBean("ContextEnvironment");
        ObjectName oname =
                MBeanUtils.createObjectName(managed.getDomain(), env);
        return (oname.toString());

    }


    /**
     * Add a resource reference for this web application.
     *
     * @param resourceName New resource reference name
     */
    public String addResource(String resourceName, String type)
            throws MalformedObjectNameException
    {

        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return null;
        }
        ContextResource resource = nresources.findResource(resourceName);
        if (resource != null)
        {
            throw new IllegalArgumentException
                    ("Invalid resource name - already exists'" + resourceName + "'");
        }
        resource = new ContextResource();
        resource.setName(resourceName);
        resource.setType(type);
        nresources.addResource(resource);

        // Return the corresponding MBean name
        ManagedBean managed = registry.findManagedBean("ContextResource");
        ObjectName oname =
                MBeanUtils.createObjectName(managed.getDomain(), resource);
        return (oname.toString());
    }


    /**
     * Add a resource link for this web application.
     *
     * @param resourceLinkName New resource link name
     */
    public String addResourceLink(String resourceLinkName, String global,
                                  String name, String type) throws MalformedObjectNameException
    {

        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return null;
        }
        ContextResourceLink resourceLink =
                nresources.findResourceLink(resourceLinkName);
        if (resourceLink != null)
        {
            throw new IllegalArgumentException
                    ("Invalid resource link name - already exists'" +
                            resourceLinkName + "'");
        }
        resourceLink = new ContextResourceLink();
        resourceLink.setGlobal(global);
        resourceLink.setName(resourceLinkName);
        resourceLink.setType(type);
        nresources.addResourceLink(resourceLink);

        // Return the corresponding MBean name
        ManagedBean managed = registry.findManagedBean("ContextResourceLink");
        ObjectName oname =
                MBeanUtils.createObjectName(managed.getDomain(), resourceLink);
        return (oname.toString());
    }


    /**
     * Remove any environment entry with the specified name.
     *
     * @param envName Name of the environment entry to remove
     */
    public void removeEnvironment(String envName)
    {

        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return;
        }
        ContextEnvironment env = nresources.findEnvironment(envName);
        if (env == null)
        {
            throw new IllegalArgumentException
                    ("Invalid environment name '" + envName + "'");
        }
        nresources.removeEnvironment(envName);

    }


    /**
     * Remove any resource reference with the specified name.
     *
     * @param resourceName Name of the resource reference to remove
     */
    public void removeResource(String resourceName)
    {

        resourceName = ObjectName.unquote(resourceName);
        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return;
        }
        ContextResource resource = nresources.findResource(resourceName);
        if (resource == null)
        {
            throw new IllegalArgumentException
                    ("Invalid resource name '" + resourceName + "'");
        }
        nresources.removeResource(resourceName);
    }


    /**
     * Remove any resource link with the specified name.
     *
     * @param resourceLinkName Name of the resource reference to remove
     */
    public void removeResourceLink(String resourceLinkName)
    {

        resourceLinkName = ObjectName.unquote(resourceLinkName);
        NamingResources nresources = getNamingResources();
        if (nresources == null)
        {
            return;
        }
        ContextResourceLink resource = nresources.findResourceLink(resourceLinkName);
        if (resource == null)
        {
            throw new IllegalArgumentException
                    ("Invalid resource name '" + resourceLinkName + "'");
        }
        nresources.removeResourceLink(resourceLinkName);
    }


}
