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
 * <p>Representation of a message destination reference for a web application,
 * as represented in a <code>&lt;message-destination-ref&gt;</code> element
 * in the deployment descriptor.</p>
 *
 * @author Craig R. McClanahan
 * @since Tomcat 5.0
 */

public class MessageDestinationRef implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The NamingResources with which we are associated (if any).
     */
    protected NamingResources resources = null;
    /**
     * The description of this destination ref.
     */
    private String description = null;
    /**
     * The link of this destination ref.
     */
    private String link = null;
    /**
     * The name of this destination ref.
     */
    private String name = null;
    /**
     * The type of this destination ref.
     */
    private String type = null;
    /**
     * The usage of this destination ref.
     */
    private String usage = null;

    public String getDescription()
    {
        return (this.description);
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getLink()
    {
        return (this.link);
    }

    public void setLink(String link)
    {
        this.link = link;
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

    public String getUsage()
    {
        return (this.usage);
    }


    // --------------------------------------------------------- Public Methods

    public void setUsage(String usage)
    {
        this.usage = usage;
    }


    // -------------------------------------------------------- Package Methods

    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("MessageDestination[");
        sb.append("name=");
        sb.append(name);
        if (link != null)
        {
            sb.append(", link=");
            sb.append(link);
        }
        if (type != null)
        {
            sb.append(", type=");
            sb.append(type);
        }
        if (usage != null)
        {
            sb.append(", usage=");
            sb.append(usage);
        }
        if (description != null)
        {
            sb.append(", description=");
            sb.append(description);
        }
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
