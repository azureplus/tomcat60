/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net;

import javax.net.ssl.SSLSession;
import java.net.Socket;

/* SSLImplementation:

   Abstract factory and base class for all SSL implementations.

   @author EKR
*/
abstract public class SSLImplementation
{
    // The default implementations in our search path
    private static final String JSSEImplementationClass =
            "org.apache.tomcat.util.net.jsse.JSSEImplementation";
    private static final String[] implementations =
            {
                    JSSEImplementationClass
            };
    private static org.apache.juli.logging.Log logger =
            org.apache.juli.logging.LogFactory.getLog(SSLImplementation.class);

    public static SSLImplementation getInstance() throws ClassNotFoundException
    {
        for (int i = 0; i < implementations.length; i++)
        {
            try
            {
                SSLImplementation impl =
                        getInstance(implementations[i]);
                return impl;
            }
            catch (Exception e)
            {
                if (logger.isTraceEnabled())
                    logger.trace("Error creating " + implementations[i], e);
            }
        }

        // If we can't instantiate any of these
        throw new ClassNotFoundException("Can't find any SSL implementation");
    }

    public static SSLImplementation getInstance(String className)
            throws ClassNotFoundException
    {
        if (className == null) return getInstance();

        try
        {
            // Workaround for the J2SE 1.4.x classloading problem (under Solaris).
            // Class.forName(..) fails without creating class using new.
            // This is an ugly workaround. 
            if (JSSEImplementationClass.equals(className))
            {
                return new org.apache.tomcat.util.net.jsse.JSSEImplementation();
            }
            Class<?> clazz = Class.forName(className);
            return (SSLImplementation) clazz.newInstance();
        }
        catch (Exception e)
        {
            if (logger.isDebugEnabled())
                logger.debug("Error loading SSL Implementation "
                        + className, e);
            throw new ClassNotFoundException("Error loading SSL Implementation "
                    + className + " :" + e.toString());
        }
    }

    abstract public String getImplementationName();

    abstract public ServerSocketFactory getServerSocketFactory();

    abstract public ServerSocketFactory getServerSocketFactory(String sslProtocol);

    abstract public SSLSupport getSSLSupport(Socket sock);

    /**
     * @deprecated This method has been deprecated since it adds a JSSE
     * dependency to this interface. It will be removed in versions
     * after 6.0.x.
     */
    @Deprecated
    abstract public SSLSupport getSSLSupport(SSLSession session);
}    
