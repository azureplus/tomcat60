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


package org.apache.catalina.manager;


import org.apache.catalina.util.ServerInfo;
import org.apache.catalina.util.StringManager;
import org.apache.tomcat.util.modeler.Registry;

import javax.management.*;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

/**
 * This servlet will display a complete status of the HTTP/1.1 connector.
 *
 * @author Remy Maucherat
 */

public class StatusManagerServlet
        extends HttpServlet implements NotificationListener
{


    // ----------------------------------------------------- Instance Variables


    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);
    /**
     * MBean server.
     */
    protected MBeanServer mBeanServer = null;


    /**
     * Vector of protocol handlers object names.
     */
    protected Vector protocolHandlers = new Vector();


    /**
     * Vector of thread pools object names.
     */
    protected Vector threadPools = new Vector();


    /**
     * Vector of request processors object names.
     */
    protected Vector requestProcessors = new Vector();


    /**
     * Vector of global request processors object names.
     */
    protected Vector globalRequestProcessors = new Vector();
    /**
     * The debugging detail level for this servlet.
     */
    private int debug = 0;


    // --------------------------------------------------------- Public Methods

    /**
     * Initialize this servlet.
     */
    public void init() throws ServletException
    {

        // Retrieve the MBean server
        mBeanServer = Registry.getRegistry(null, null).getMBeanServer();

        // Set our properties from the initialization parameters
        String value = null;
        try
        {
            value = getServletConfig().getInitParameter("debug");
            debug = Integer.parseInt(value);
        }
        catch (Throwable t)
        {
            ;
        }

        try
        {

            // Query protocol handlers
            String onStr = "*:type=ProtocolHandler,*";
            ObjectName objectName = new ObjectName(onStr);
            Set set = mBeanServer.queryMBeans(objectName, null);
            Iterator iterator = set.iterator();
            while (iterator.hasNext())
            {
                ObjectInstance oi = (ObjectInstance) iterator.next();
                protocolHandlers.addElement(oi.getObjectName());
            }

            // Query Thread Pools
            onStr = "*:type=ThreadPool,*";
            objectName = new ObjectName(onStr);
            set = mBeanServer.queryMBeans(objectName, null);
            iterator = set.iterator();
            while (iterator.hasNext())
            {
                ObjectInstance oi = (ObjectInstance) iterator.next();
                threadPools.addElement(oi.getObjectName());
            }

            // Query Global Request Processors
            onStr = "*:type=GlobalRequestProcessor,*";
            objectName = new ObjectName(onStr);
            set = mBeanServer.queryMBeans(objectName, null);
            iterator = set.iterator();
            while (iterator.hasNext())
            {
                ObjectInstance oi = (ObjectInstance) iterator.next();
                globalRequestProcessors.addElement(oi.getObjectName());
            }

            // Query Request Processors
            onStr = "*:type=RequestProcessor,*";
            objectName = new ObjectName(onStr);
            set = mBeanServer.queryMBeans(objectName, null);
            iterator = set.iterator();
            while (iterator.hasNext())
            {
                ObjectInstance oi = (ObjectInstance) iterator.next();
                requestProcessors.addElement(oi.getObjectName());
            }

            // Register with MBean server
            onStr = "JMImplementation:type=MBeanServerDelegate";
            objectName = new ObjectName(onStr);
            mBeanServer.addNotificationListener(objectName, this, null, null);

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

    }


    /**
     * Finalize this servlet.
     */
    public void destroy()
    {

        ;       // No actions necessary

    }


    /**
     * Process a GET request for the specified resource.
     *
     * @param request  The servlet request we are processing
     * @param response The servlet response we are creating
     * @throws IOException      if an input/output error occurs
     * @throws ServletException if a servlet-specified error occurs
     */
    public void doGet(HttpServletRequest request,
                      HttpServletResponse response)
            throws IOException, ServletException
    {

        // mode is flag for HTML or XML output
        int mode = 0;
        // if ?XML=true, set the mode to XML
        if (request.getParameter("XML") != null
                && request.getParameter("XML").equals("true"))
        {
            mode = 1;
        }
        StatusTransformer.setContentType(response, mode);

        PrintWriter writer = response.getWriter();

        boolean completeStatus = false;
        if ((request.getPathInfo() != null)
                && (request.getPathInfo().equals("/all")))
        {
            completeStatus = true;
        }
        // use StatusTransformer to output status
        StatusTransformer.writeHeader(writer, mode);

        // Body Header Section
        Object[] args = new Object[2];
        args[0] = request.getContextPath();
        if (completeStatus)
        {
            args[1] = sm.getString("statusServlet.complete");
        } else
        {
            args[1] = sm.getString("statusServlet.title");
        }
        // use StatusTransformer to output status
        StatusTransformer.writeBody(writer, args, mode);

        // Manager Section
        args = new Object[9];
        args[0] = sm.getString("htmlManagerServlet.manager");
        args[1] = response.encodeURL(request.getContextPath() + "/html/list");
        args[2] = sm.getString("htmlManagerServlet.list");
        args[3] = response.encodeURL
                (request.getContextPath() + "/" +
                        sm.getString("htmlManagerServlet.helpHtmlManagerFile"));
        args[4] = sm.getString("htmlManagerServlet.helpHtmlManager");
        args[5] = response.encodeURL
                (request.getContextPath() + "/" +
                        sm.getString("htmlManagerServlet.helpManagerFile"));
        args[6] = sm.getString("htmlManagerServlet.helpManager");
        if (completeStatus)
        {
            args[7] = response.encodeURL
                    (request.getContextPath() + "/status");
            args[8] = sm.getString("statusServlet.title");
        } else
        {
            args[7] = response.encodeURL
                    (request.getContextPath() + "/status/all");
            args[8] = sm.getString("statusServlet.complete");
        }
        // use StatusTransformer to output status
        StatusTransformer.writeManager(writer, args, mode);

        // Server Header Section
        args = new Object[7];
        args[0] = sm.getString("htmlManagerServlet.serverTitle");
        args[1] = sm.getString("htmlManagerServlet.serverVersion");
        args[2] = sm.getString("htmlManagerServlet.serverJVMVersion");
        args[3] = sm.getString("htmlManagerServlet.serverJVMVendor");
        args[4] = sm.getString("htmlManagerServlet.serverOSName");
        args[5] = sm.getString("htmlManagerServlet.serverOSVersion");
        args[6] = sm.getString("htmlManagerServlet.serverOSArch");
        // use StatusTransformer to output status
        StatusTransformer.writePageHeading(writer, args, mode);

        // Server Row Section
        args = new Object[6];
        args[0] = ServerInfo.getServerInfo();
        args[1] = System.getProperty("java.runtime.version");
        args[2] = System.getProperty("java.vm.vendor");
        args[3] = System.getProperty("os.name");
        args[4] = System.getProperty("os.version");
        args[5] = System.getProperty("os.arch");
        // use StatusTransformer to output status
        StatusTransformer.writeServerInfo(writer, args, mode);

        try
        {

            // Display operating system statistics using APR if available
            StatusTransformer.writeOSState(writer, mode);

            // Display virtual machine statistics
            StatusTransformer.writeVMState(writer, mode);

            Enumeration enumeration = threadPools.elements();
            while (enumeration.hasMoreElements())
            {
                ObjectName objectName = (ObjectName) enumeration.nextElement();
                String name = objectName.getKeyProperty("name");
                // use StatusTransformer to output status
                StatusTransformer.writeConnectorState
                        (writer, objectName,
                                name, mBeanServer, globalRequestProcessors,
                                requestProcessors, mode);
            }

            if ((request.getPathInfo() != null)
                    && (request.getPathInfo().equals("/all")))
            {
                // Note: Retrieving the full status is much slower
                // use StatusTransformer to output status
                StatusTransformer.writeDetailedState
                        (writer, mBeanServer, mode);
            }

        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }

        // use StatusTransformer to output status
        StatusTransformer.writeFooter(writer, mode);

    }

    // ------------------------------------------- NotificationListener Methods


    public void handleNotification(Notification notification,
                                   java.lang.Object handback)
    {

        if (notification instanceof MBeanServerNotification)
        {
            ObjectName objectName =
                    ((MBeanServerNotification) notification).getMBeanName();
            if (notification.getType().equals
                    (MBeanServerNotification.REGISTRATION_NOTIFICATION))
            {
                String type = objectName.getKeyProperty("type");
                if (type != null)
                {
                    if (type.equals("ProtocolHandler"))
                    {
                        protocolHandlers.addElement(objectName);
                    } else if (type.equals("ThreadPool"))
                    {
                        threadPools.addElement(objectName);
                    } else if (type.equals("GlobalRequestProcessor"))
                    {
                        globalRequestProcessors.addElement(objectName);
                    } else if (type.equals("RequestProcessor"))
                    {
                        requestProcessors.addElement(objectName);
                    }
                }
            } else if (notification.getType().equals
                    (MBeanServerNotification.UNREGISTRATION_NOTIFICATION))
            {
                String type = objectName.getKeyProperty("type");
                if (type != null)
                {
                    if (type.equals("ProtocolHandler"))
                    {
                        protocolHandlers.removeElement(objectName);
                    } else if (type.equals("ThreadPool"))
                    {
                        threadPools.removeElement(objectName);
                    } else if (type.equals("GlobalRequestProcessor"))
                    {
                        globalRequestProcessors.removeElement(objectName);
                    } else if (type.equals("RequestProcessor"))
                    {
                        requestProcessors.removeElement(objectName);
                    }
                }
                String j2eeType = objectName.getKeyProperty("j2eeType");
                if (j2eeType != null)
                {

                }
            }
        }

    }


}
