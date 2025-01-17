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
 * Representation of a resource reference for a web application, as
 * represented in a <code>&lt;resource-ref&gt;</code> element in the
 * deployment descriptor.
 *
 * @author Craig R. McClanahan
 * @author Peter Rossbach (pero@apache.org)
 */

public class ContextResource extends ResourceBase implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The authorization requirement for this resource
     * (<code>Application</code> or <code>Container</code>).
     */
    private String auth = null;
    /**
     * The sharing scope of this resource factory (<code>Shareable</code>
     * or <code>Unshareable</code>).
     */
    private String scope = "Shareable";

    public String getAuth()
    {
        return (this.auth);
    }

    public void setAuth(String auth)
    {
        this.auth = auth;
    }

    public String getScope()
    {
        return (this.scope);
    }

    public void setScope(String scope)
    {
        this.scope = scope;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("ContextResource[");
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
        if (auth != null)
        {
            sb.append(", auth=");
            sb.append(auth);
        }
        if (scope != null)
        {
            sb.append(", scope=");
            sb.append(scope);
        }
        sb.append("]");
        return (sb.toString());

    }

}
