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


/**
 * <p>Representation of a message destination for a web application, as
 * represented in a <code>&lt;message-destination&gt;</code> element
 * in the deployment descriptor.</p>
 *
 * @author Craig R. McClanahan
 * @since Tomcat 5.0
 */

public class MessageDestination
{


    // ------------------------------------------------------------- Properties


    /**
     * The description of this destination.
     */
    private String description = null;
    /**
     * The display name of this destination.
     */
    private String displayName = null;
    /**
     * The large icon of this destination.
     */
    private String largeIcon = null;
    /**
     * The name of this destination.
     */
    private String name = null;
    /**
     * The small icon of this destination.
     */
    private String smallIcon = null;

    public String getDescription()
    {
        return (this.description);
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getDisplayName()
    {
        return (this.displayName);
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getLargeIcon()
    {
        return (this.largeIcon);
    }

    public void setLargeIcon(String largeIcon)
    {
        this.largeIcon = largeIcon;
    }

    public String getName()
    {
        return (this.name);
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getSmallIcon()
    {
        return (this.smallIcon);
    }

    public void setSmallIcon(String smallIcon)
    {
        this.smallIcon = smallIcon;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("MessageDestination[");
        sb.append("name=");
        sb.append(name);
        if (displayName != null)
        {
            sb.append(", displayName=");
            sb.append(displayName);
        }
        if (largeIcon != null)
        {
            sb.append(", largeIcon=");
            sb.append(largeIcon);
        }
        if (smallIcon != null)
        {
            sb.append(", smallIcon=");
            sb.append(smallIcon);
        }
        if (description != null)
        {
            sb.append(", description=");
            sb.append(description);
        }
        sb.append("]");
        return (sb.toString());

    }


}
