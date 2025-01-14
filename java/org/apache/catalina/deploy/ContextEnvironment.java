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


/**
 * Representation of an application environment entry, as represented in
 * an <code>&lt;env-entry&gt;</code> element in the deployment descriptor.
 *
 * @author Craig R. McClanahan
 */

public class ContextEnvironment implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The NamingResources with which we are associated (if any).
     */
    protected NamingResources resources = null;
    /**
     * The description of this environment entry.
     */
    private String description = null;
    /**
     * The name of this environment entry.
     */
    private String name = null;
    /**
     * Does this environment entry allow overrides by the application
     * deployment descriptor?
     */
    private boolean override = true;
    /**
     * The type of this environment entry.
     */
    private String type = null;
    /**
     * The value of this environment entry.
     */
    private String value = null;

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

    public boolean getOverride()
    {
        return (this.override);
    }

    public void setOverride(boolean override)
    {
        this.override = override;
    }

    public String getType()
    {
        return (this.type);
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getValue()
    {
        return (this.value);
    }

    // --------------------------------------------------------- Public Methods

    public void setValue(String value)
    {
        this.value = value;
    }


    // -------------------------------------------------------- Package Methods

    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("ContextEnvironment[");
        sb.append("name=");
        sb.append(name);
        if (description != null)
        {
            sb.append(", description=");
            sb.append(description);
        }
        if (type != null)
        {
            sb.append(", type=");
            sb.append(type);
        }
        if (value != null)
        {
            sb.append(", value=");
            sb.append(value);
        }
        sb.append(", override=");
        sb.append(override);
        sb.append("]");
        return (sb.toString());

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
