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
import java.util.Map;


/**
 * Representation of a filter definition for a web application, as represented
 * in a <code>&lt;filter&gt;</code> element in the deployment descriptor.
 *
 * @author Craig R. McClanahan
 */

public class FilterDef implements Serializable
{


    // ------------------------------------------------------------- Properties


    /**
     * The description of this filter.
     */
    private String description = null;
    /**
     * The display name of this filter.
     */
    private String displayName = null;
    /**
     * The fully qualified name of the Java class that implements this filter.
     */
    private String filterClass = null;
    /**
     * The name of this filter, which must be unique among the filters
     * defined for a particular web application.
     */
    private String filterName = null;
    /**
     * The large icon associated with this filter.
     */
    private String largeIcon = null;
    /**
     * The set of initialization parameters for this filter, keyed by
     * parameter name.
     */
    private Map parameters = new HashMap();
    /**
     * The small icon associated with this filter.
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

    public String getFilterClass()
    {
        return (this.filterClass);
    }

    public void setFilterClass(String filterClass)
    {
        this.filterClass = filterClass;
    }

    public String getFilterName()
    {
        return (this.filterName);
    }

    public void setFilterName(String filterName)
    {
        this.filterName = filterName;
    }

    public String getLargeIcon()
    {
        return (this.largeIcon);
    }

    public void setLargeIcon(String largeIcon)
    {
        this.largeIcon = largeIcon;
    }

    public Map getParameterMap()
    {

        return (this.parameters);

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
     * Add an initialization parameter to the set of parameters associated
     * with this filter.
     *
     * @param name  The initialization parameter name
     * @param value The initialization parameter value
     */
    public void addInitParameter(String name, String value)
    {

        parameters.put(name, value);

    }


    /**
     * Render a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("FilterDef[");
        sb.append("filterName=");
        sb.append(this.filterName);
        sb.append(", filterClass=");
        sb.append(this.filterClass);
        sb.append("]");
        return (sb.toString());

    }


}
