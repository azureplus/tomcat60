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


package org.apache.catalina.core;


public class Constants
{

    public static final String Package = "org.apache.catalina.core";
    public static final int MAJOR_VERSION = 2;
    public static final int MINOR_VERSION = 5;

    public static final String JSP_SERVLET_CLASS =
            "org.apache.jasper.servlet.JspServlet";
    public static final String JSP_SERVLET_NAME = "jsp";
    public static final String PRECOMPILE =
            System.getProperty("org.apache.jasper.Constants.PRECOMPILE",
                    "jsp_precompile");

}
