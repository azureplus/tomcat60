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


import org.apache.catalina.deploy.ContextResourceLink;
import org.apache.catalina.deploy.NamingResources;
import org.apache.tomcat.util.modeler.BaseModelMBean;

import javax.management.*;
import javax.management.modelmbean.InvalidTargetObjectTypeException;


/**
 * <p>A <strong>ModelMBean</strong> implementation for the
 * <code>org.apache.catalina.deploy.ContextResourceLink</code> component.</p>
 *
 * @author Amy Roh
 */

public class ContextResourceLinkMBean extends BaseModelMBean
{


    // ----------------------------------------------------------- Constructors


    /**
     * Construct a <code>ModelMBean</code> with default
     * <code>ModelMBeanInfo</code> information.
     *
     * @throws MBeanException             if the initializer of an object
     *                                    throws an exception
     * @throws RuntimeOperationsException if an IllegalArgumentException
     *                                    occurs
     */
    public ContextResourceLinkMBean()
            throws MBeanException, RuntimeOperationsException
    {

        super();

    }


    // ----------------------------------------------------- Instance Variables


    // ------------------------------------------------------------- Attributes


    /**
     * Set the value of a specific attribute of this MBean.
     *
     * @param attribute The identification of the attribute to be set
     *                  and the new value
     * @throws AttributeNotFoundException if this attribute is not
     *                                    supported by this MBean
     * @throws MBeanException             if the initializer of an object
     *                                    throws an exception
     * @throws ReflectionException        if a Java reflection exception
     *                                    occurs when invoking the getter
     */
    public void setAttribute(Attribute attribute)
            throws AttributeNotFoundException, MBeanException,
            ReflectionException
    {

        super.setAttribute(attribute);

        ContextResourceLink crl = null;
        try
        {
            crl = (ContextResourceLink) getManagedResource();
        }
        catch (InstanceNotFoundException e)
        {
            throw new MBeanException(e);
        }
        catch (InvalidTargetObjectTypeException e)
        {
            throw new MBeanException(e);
        }

        // cannot use side-efects.  It's removed and added back each time 
        // there is a modification in a resource.
        NamingResources nr = crl.getNamingResources();
        nr.removeResourceLink(crl.getName());
        nr.addResourceLink(crl);
    }

}
