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
 * Representation of an EJB resource reference for a web application, as
 * represented in a <code>&lt;ejb-ref&gt;</code> element in the
 * deployment descriptor.
 *
 * @author Craig R. McClanahan
 * @author Peter Rossbach (pero@apache.org)
 */

public class ContextEjb extends ResourceBase implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The name of the EJB home implementation class.
     */
    private String home = null;
    /**
     * The link to a J2EE EJB definition.
     */
    private String link = null;
    /**
     * The name of the EJB remote implementation class.
     */
    private String remote = null;

    public String getHome()
    {
        return (this.home);
    }

    public void setHome(String home)
    {
        this.home = home;
    }

    public String getLink()
    {
        return (this.link);
    }

    public void setLink(String link)
    {
        this.link = link;
    }

    public String getRemote()
    {
        return (this.remote);
    }

    public void setRemote(String remote)
    {
        this.remote = remote;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("ContextEjb[");
        sb.append("name=");
        sb.append(getName());
        if (getDescription() != null)
        {
            sb.append(", description=");
            sb.append(getDescription());
        }
        if (getType() != null)
        {
            sb.append(", type=");
            sb.append(getType());
        }
        if (home != null)
        {
            sb.append(", home=");
            sb.append(home);
        }
        if (remote != null)
        {
            sb.append(", remote=");
            sb.append(remote);
        }
        if (link != null)
        {
            sb.append(", link=");
            sb.append(link);
        }
        sb.append("]");
        return (sb.toString());

    }


}
