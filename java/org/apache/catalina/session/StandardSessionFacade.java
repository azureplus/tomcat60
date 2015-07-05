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


package org.apache.catalina.session;


import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import java.util.Enumeration;


/**
 * Facade for the StandardSession object.
 *
 * @author Remy Maucherat
 */

public class StandardSessionFacade
        implements HttpSession
{


    // ----------------------------------------------------------- Constructors


    /**
     * Wrapped session object.
     */
    private HttpSession session = null;


    /**
     * Construct a new session facade.
     */
    public StandardSessionFacade(StandardSession session)
    {
        super();
        this.session = (HttpSession) session;
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Construct a new session facade.
     */
    public StandardSessionFacade(HttpSession session)
    {
        super();
        this.session = session;
    }


    // ---------------------------------------------------- HttpSession Methods

    public long getCreationTime()
    {
        return session.getCreationTime();
    }


    public String getId()
    {
        return session.getId();
    }


    public long getLastAccessedTime()
    {
        return session.getLastAccessedTime();
    }


    public ServletContext getServletContext()
    {
        // FIXME : Facade this object ?
        return session.getServletContext();
    }

    public int getMaxInactiveInterval()
    {
        return session.getMaxInactiveInterval();
    }

    public void setMaxInactiveInterval(int interval)
    {
        session.setMaxInactiveInterval(interval);
    }

    public HttpSessionContext getSessionContext()
    {
        return session.getSessionContext();
    }


    public Object getAttribute(String name)
    {
        return session.getAttribute(name);
    }


    public Object getValue(String name)
    {
        return session.getAttribute(name);
    }


    public Enumeration getAttributeNames()
    {
        return session.getAttributeNames();
    }


    public String[] getValueNames()
    {
        return session.getValueNames();
    }


    public void setAttribute(String name, Object value)
    {
        session.setAttribute(name, value);
    }


    public void putValue(String name, Object value)
    {
        session.setAttribute(name, value);
    }


    public void removeAttribute(String name)
    {
        session.removeAttribute(name);
    }


    public void removeValue(String name)
    {
        session.removeAttribute(name);
    }


    public void invalidate()
    {
        session.invalidate();
    }


    public boolean isNew()
    {
        return session.isNew();
    }


}
