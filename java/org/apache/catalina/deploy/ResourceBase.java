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


package org.apache.catalina.deploy;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Iterator;


/**
 * Representation of an Context element
 *
 * @author Peter Rossbach (pero@apache.org)
 */

public class ResourceBase implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The NamingResources with which we are associated (if any).
     */
    protected NamingResources resources = null;
    /**
     * The description of this Context Element.
     */
    private String description = null;
    /**
     * The name of this context Element.
     */
    private String name = null;
    /**
     * The name of the EJB bean implementation class.
     */
    private String type = null;
    /**
     * Holder for our configured properties.
     */
    private HashMap properties = new HashMap();

    public String getDescription()
    {
        return (this.description);
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getName()
    {
        return (this.name);
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getType()
    {
        return (this.type);
    }

    public void setType(String type)
    {
        this.type = type;
    }

    /**
     * Return a configured property.
     */
    public Object getProperty(String name)
    {
        return properties.get(name);
    }

    /**
     * Set a configured property.
     */
    public void setProperty(String name, Object value)
    {
        properties.put(name, value);
    }

    /**
     * remove a configured property.
     */
    public void removeProperty(String name)
    {
        properties.remove(name);
    }


    // -------------------------------------------------------- Package Methods

    /**
     * List properties.
     */
    public Iterator listProperties()
    {
        return properties.keySet().iterator();
    }

    public NamingResources getNamingResources()
    {
        return (this.resources);
    }

    void setNamingResources(NamingResources resources)
    {
        this.resources = resources;
    }


}
