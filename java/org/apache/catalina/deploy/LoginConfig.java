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


import org.apache.catalina.util.RequestUtil;

import java.io.Serializable;


/**
 * Representation of a login configuration element for a web application,
 * as represented in a <code>&lt;login-config&gt;</code> element in the
 * deployment descriptor.
 *
 * @author Craig R. McClanahan
 */

public class LoginConfig implements Serializable
{


    // ----------------------------------------------------------- Constructors


    /**
     * The authentication method to use for application login.  Must be
     * BASIC, DIGEST, FORM, or CLIENT-CERT.
     */
    private String authMethod = null;
    /**
     * The context-relative URI of the error page for form login.
     */
    private String errorPage = null;


    // ------------------------------------------------------------- Properties
    /**
     * The context-relative URI of the login page for form login.
     */
    private String loginPage = null;
    /**
     * The realm name used when challenging the user for authentication
     * credentials.
     */
    private String realmName = null;

    /**
     * Construct a new LoginConfig with default properties.
     */
    public LoginConfig()
    {

        super();

    }


    /**
     * Construct a new LoginConfig with the specified properties.
     *
     * @param authMethod The authentication method
     * @param realmName  The realm name
     * @param loginPage  The login page URI
     * @param errorPage  The error page URI
     */
    public LoginConfig(String authMethod, String realmName,
                       String loginPage, String errorPage)
    {

        super();
        setAuthMethod(authMethod);
        setRealmName(realmName);
        setLoginPage(loginPage);
        setErrorPage(errorPage);

    }

    public String getAuthMethod()
    {
        return (this.authMethod);
    }

    public void setAuthMethod(String authMethod)
    {
        this.authMethod = authMethod;
    }

    public String getErrorPage()
    {
        return (this.errorPage);
    }

    public void setErrorPage(String errorPage)
    {
        //        if ((errorPage == null) || !errorPage.startsWith("/"))
        //            throw new IllegalArgumentException
        //                ("Error Page resource path must start with a '/'");
        this.errorPage = RequestUtil.URLDecode(errorPage);
    }

    public String getLoginPage()
    {
        return (this.loginPage);
    }

    public void setLoginPage(String loginPage)
    {
        //        if ((loginPage == null) || !loginPage.startsWith("/"))
        //            throw new IllegalArgumentException
        //                ("Login Page resource path must start with a '/'");
        this.loginPage = RequestUtil.URLDecode(loginPage);
    }

    public String getRealmName()
    {
        return (this.realmName);
    }

    public void setRealmName(String realmName)
    {
        this.realmName = realmName;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Return a String representation of this object.
     */
    public String toString()
    {

        StringBuffer sb = new StringBuffer("LoginConfig[");
        sb.append("authMethod=");
        sb.append(authMethod);
        if (realmName != null)
        {
            sb.append(", realmName=");
            sb.append(realmName);
        }
        if (loginPage != null)
        {
            sb.append(", loginPage=");
            sb.append(loginPage);
        }
        if (errorPage != null)
        {
            sb.append(", errorPage=");
            sb.append(errorPage);
        }
        sb.append("]");
        return (sb.toString());

    }


}
