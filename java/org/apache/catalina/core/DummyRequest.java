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


import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Response;
import org.apache.tomcat.util.buf.MessageBytes;

import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;


/**
 * Dummy request object, used for request dispatcher mapping, as well as
 * JSP precompilation.
 *
 * @author Remy Maucherat
 */

public class DummyRequest
        implements HttpServletRequest
{

    private static Enumeration dummyEnum = new Enumeration()
    {
        public boolean hasMoreElements()
        {
            return false;
        }

        public Object nextElement()
        {
            return null;
        }
    };
    protected String contextPath = null;
    protected String decodedURI = null;
    protected String queryString = null;
    protected String pathInfo = null;
    protected String servletPath = null;
    protected Wrapper wrapper = null;
    protected FilterChain filterChain = null;

    public DummyRequest()
    {
    }

    public DummyRequest(String contextPath, String decodedURI,
                        String queryString)
    {
        this.contextPath = contextPath;
        this.decodedURI = decodedURI;
        this.queryString = queryString;
    }

    public String getContextPath()
    {
        return (contextPath);
    }

    public void setContextPath(String path)
    {
    }

    public MessageBytes getContextPathMB()
    {
        return null;
    }

    public ServletRequest getRequest()
    {
        return (this);
    }

    public String getDecodedRequestURI()
    {
        return decodedURI;
    }

    public void setDecodedRequestURI(String uri)
    {
    }

    public MessageBytes getDecodedRequestURIMB()
    {
        return null;
    }

    public FilterChain getFilterChain()
    {
        return (this.filterChain);
    }

    public void setFilterChain(FilterChain filterChain)
    {
        this.filterChain = filterChain;
    }

    public String getQueryString()
    {
        return queryString;
    }

    public void setQueryString(String query)
    {
        queryString = query;
    }

    public String getPathInfo()
    {
        return pathInfo;
    }

    public void setPathInfo(String path)
    {
        pathInfo = path;
    }

    public MessageBytes getPathInfoMB()
    {
        return null;
    }

    public MessageBytes getRequestPathMB()
    {
        return null;
    }

    public String getServletPath()
    {
        return servletPath;
    }

    public void setServletPath(String path)
    {
        servletPath = path;
    }

    public MessageBytes getServletPathMB()
    {
        return null;
    }

    public Wrapper getWrapper()
    {
        return (this.wrapper);
    }

    public void setWrapper(Wrapper wrapper)
    {
        this.wrapper = wrapper;
    }

    public String getAuthorization()
    {
        return null;
    }

    public void setAuthorization(String authorization)
    {
    }

    public Connector getConnector()
    {
        return null;
    }

    public void setConnector(Connector connector)
    {
    }

    public Context getContext()
    {
        return null;
    }

    public void setContext(Context context)
    {
    }

    public Host getHost()
    {
        return null;
    }

    public void setHost(Host host)
    {
    }

    public String getInfo()
    {
        return null;
    }

    public Response getResponse()
    {
        return null;
    }

    public void setResponse(Response response)
    {
    }

    public Socket getSocket()
    {
        return null;
    }

    public void setSocket(Socket socket)
    {
    }

    public InputStream getStream()
    {
        return null;
    }

    public void setStream(InputStream input)
    {
    }

    public void addLocale(Locale locale)
    {
    }

    public ServletInputStream createInputStream() throws IOException
    {
        return null;
    }

    public void finishRequest() throws IOException
    {
    }

    public Object getNote(String name)
    {
        return null;
    }

    public Iterator getNoteNames()
    {
        return null;
    }

    public void removeNote(String name)
    {
    }

    public void setNote(String name, Object value)
    {
    }

    public Object getAttribute(String name)
    {
        return null;
    }

    public Enumeration getAttributeNames()
    {
        return null;
    }

    public String getCharacterEncoding()
    {
        return null;
    }

    public void setCharacterEncoding(String enc)
            throws UnsupportedEncodingException
    {
    }

    public int getContentLength()
    {
        return -1;
    }

    public void setContentLength(int length)
    {
    }

    public String getContentType()
    {
        return null;
    }

    public void setContentType(String type)
    {
    }

    public ServletInputStream getInputStream() throws IOException
    {
        return null;
    }

    public Locale getLocale()
    {
        return null;
    }

    public Enumeration getLocales()
    {
        return null;
    }

    public String getProtocol()
    {
        return null;
    }

    public void setProtocol(String protocol)
    {
    }

    public BufferedReader getReader() throws IOException
    {
        return null;
    }

    public String getRealPath(String path)
    {
        return null;
    }

    public String getRemoteAddr()
    {
        return null;
    }

    public void setRemoteAddr(String remoteAddr)
    {
    }

    public String getRemoteHost()
    {
        return null;
    }

    public void setRemoteHost(String remoteHost)
    {
    }

    public String getScheme()
    {
        return null;
    }

    public void setScheme(String scheme)
    {
    }

    public String getServerName()
    {
        return null;
    }

    public void setServerName(String name)
    {
    }

    public int getServerPort()
    {
        return -1;
    }

    public void setServerPort(int port)
    {
    }

    public boolean isSecure()
    {
        return false;
    }

    public void setSecure(boolean secure)
    {
    }

    public void removeAttribute(String name)
    {
    }

    public void setAttribute(String name, Object value)
    {
    }

    public void addCookie(Cookie cookie)
    {
    }

    public void addHeader(String name, String value)
    {
    }

    public void addParameter(String name, String values[])
    {
    }

    public void clearCookies()
    {
    }

    public void clearHeaders()
    {
    }

    public void clearLocales()
    {
    }

    public void clearParameters()
    {
    }

    public void recycle()
    {
    }

    public void setRequestedSessionCookie(boolean flag)
    {
    }

    public void setRequestedSessionURL(boolean flag)
    {
    }

    public String getParameter(String name)
    {
        return null;
    }

    public Map getParameterMap()
    {
        return null;
    }

    public Enumeration getParameterNames()
    {
        return dummyEnum;
    }

    public String[] getParameterValues(String name)
    {
        return null;
    }

    public RequestDispatcher getRequestDispatcher(String path)
    {
        return null;
    }

    public String getAuthType()
    {
        return null;
    }

    public void setAuthType(String authType)
    {
    }

    public Cookie[] getCookies()
    {
        return null;
    }

    public long getDateHeader(String name)
    {
        return -1;
    }

    public String getHeader(String name)
    {
        return null;
    }

    public Enumeration getHeaders(String name)
    {
        return null;
    }

    public Enumeration getHeaderNames()
    {
        return null;
    }

    public int getIntHeader(String name)
    {
        return -1;
    }

    public String getMethod()
    {
        return null;
    }

    public void setMethod(String method)
    {
    }

    public String getPathTranslated()
    {
        return null;
    }

    public String getRemoteUser()
    {
        return null;
    }

    public String getRequestedSessionId()
    {
        return null;
    }

    public void setRequestedSessionId(String id)
    {
    }

    public String getRequestURI()
    {
        return null;
    }

    public void setRequestURI(String uri)
    {
    }

    public StringBuffer getRequestURL()
    {
        return null;
    }

    public HttpSession getSession()
    {
        return null;
    }

    public HttpSession getSession(boolean create)
    {
        return null;
    }

    public boolean isRequestedSessionIdFromCookie()
    {
        return false;
    }

    public boolean isRequestedSessionIdFromURL()
    {
        return false;
    }

    public boolean isRequestedSessionIdFromUrl()
    {
        return false;
    }

    public boolean isRequestedSessionIdValid()
    {
        return false;
    }

    public boolean isUserInRole(String role)
    {
        return false;
    }

    public Principal getUserPrincipal()
    {
        return null;
    }

    public void setUserPrincipal(Principal principal)
    {
    }

    public String getLocalAddr()
    {
        return null;
    }

    public String getLocalName()
    {
        return null;
    }

    public int getLocalPort()
    {
        return -1;
    }

    public int getRemotePort()
    {
        return -1;
    }

}

