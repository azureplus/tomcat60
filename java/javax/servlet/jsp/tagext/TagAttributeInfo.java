/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.servlet.jsp.tagext;

/**
 * Information on the attributes of a Tag, available at translation time. This
 * class is instantiated from the Tag Library Descriptor file (TLD).
 * <p/>
 * <p/>
 * Only the information needed to generate code is included here. Other
 * information like SCHEMA for validation belongs elsewhere.
 */

public class TagAttributeInfo
{
    /**
     * "id" is wired in to be ID. There is no real benefit in having it be
     * something else IDREFs are not handled any differently.
     */

    public static final String ID = "id";
    /*
     * private fields
     */
    private String name;
    private String type;
    private boolean reqTime;
    private boolean required;
    /*
     * private fields for JSP 2.0
     */
    private boolean fragment;
    /*
     * private fields for JSP 2.1
     */
    private String description;
    private boolean deferredValue;
    private boolean deferredMethod;
    private String expectedTypeName;
    private String methodSignature;

    /**
     * Constructor for TagAttributeInfo. This class is to be instantiated only
     * from the TagLibrary code under request from some JSP code that is parsing
     * a TLD (Tag Library Descriptor).
     *
     * @param name     The name of the attribute.
     * @param required If this attribute is required in tag instances.
     * @param type     The name of the type of the attribute.
     * @param reqTime  Whether this attribute holds a request-time Attribute.
     */

    public TagAttributeInfo(String name, boolean required, String type,
                            boolean reqTime)
    {
        this.name = name;
        this.required = required;
        this.type = type;
        this.reqTime = reqTime;
    }

    /**
     * JSP 2.0 Constructor for TagAttributeInfo. This class is to be
     * instantiated only from the TagLibrary code under request from some JSP
     * code that is parsing a TLD (Tag Library Descriptor).
     *
     * @param name     The name of the attribute.
     * @param required If this attribute is required in tag instances.
     * @param type     The name of the type of the attribute.
     * @param reqTime  Whether this attribute holds a request-time Attribute.
     * @param fragment Whether this attribute is of type JspFragment
     * @since 2.0
     */

    public TagAttributeInfo(String name, boolean required, String type,
                            boolean reqTime, boolean fragment)
    {
        this(name, required, type, reqTime);
        this.fragment = fragment;
    }

    /**
     * @since JSP 2.1
     */
    public TagAttributeInfo(String name, boolean required, String type,
                            boolean reqTime, boolean fragment, String description,
                            boolean deferredValue, boolean deferredMethod,
                            String expectedTypeName, String methodSignature)
    {
        this(name, required, type, reqTime, fragment);
        this.description = description;
        this.deferredValue = deferredValue;
        this.deferredMethod = deferredMethod;
        this.expectedTypeName = expectedTypeName;
        this.methodSignature = methodSignature;
    }

    /**
     * Convenience static method that goes through an array of TagAttributeInfo
     * objects and looks for "id".
     *
     * @param a An array of TagAttributeInfo
     * @return The TagAttributeInfo reference with name "id"
     */
    public static TagAttributeInfo getIdAttribute(TagAttributeInfo a[])
    {
        for (int i = 0; i < a.length; i++)
        {
            if (a[i].getName().equals(ID))
            {
                return a[i];
            }
        }
        return null; // no such attribute
    }

    /**
     * The name of this attribute.
     *
     * @return the name of the attribute
     */

    public String getName()
    {
        return name;
    }

    /**
     * The type (as a String) of this attribute.
     *
     * @return the type of the attribute
     */

    public String getTypeName()
    {
        return type;
    }

    /**
     * Whether this attribute can hold a request-time value.
     *
     * @return if the attribute can hold a request-time value.
     */

    public boolean canBeRequestTime()
    {
        return reqTime;
    }

    /**
     * Whether this attribute is required.
     *
     * @return if the attribute is required.
     */
    public boolean isRequired()
    {
        return required;
    }

    /**
     * Whether this attribute is of type JspFragment.
     *
     * @return if the attribute is of type JspFragment
     * @since 2.0
     */
    public boolean isFragment()
    {
        return fragment;
    }

    /**
     * Returns a String representation of this TagAttributeInfo, suitable for
     * debugging purposes.
     *
     * @return a String representation of this TagAttributeInfo
     */
    public String toString()
    {
        StringBuffer b = new StringBuffer(64);
        b.append("name = " + name + " ");
        b.append("type = " + type + " ");
        b.append("reqTime = " + reqTime + " ");
        b.append("required = " + required + " ");
        b.append("fragment = " + fragment + " ");
        b.append("deferredValue = " + deferredValue + " ");
        b.append("expectedTypeName = " + expectedTypeName + " ");
        b.append("deferredMethod = " + deferredMethod + " ");
        b.append("methodSignature = " + methodSignature);
        return b.toString();
    }

    public boolean isDeferredMethod()
    {
        return deferredMethod;
    }

    public boolean isDeferredValue()
    {
        return deferredValue;
    }

    public String getDescription()
    {
        return description;
    }

    public String getExpectedTypeName()
    {
        return expectedTypeName;
    }

    public String getMethodSignature()
    {
        return methodSignature;
    }
}
