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
package javax.servlet.http;

import javax.servlet.ServletContext;
import java.util.Enumeration;

/**
 * Provides a way to identify a user across more than one page
 * request or visit to a Web site and to store information about that user.
 * <p/>
 * <p>The servlet container uses this interface to create a session
 * between an HTTP client and an HTTP server. The session persists
 * for a specified time period, across more than one connection or
 * page request from the user. A session usually corresponds to one
 * user, who may visit a site many times. The server can maintain a
 * session in many ways such as using cookies or rewriting URLs.
 * <p/>
 * <p>This interface allows servlets to
 * <ul>
 * <li>View and manipulate information about a session, such as
 * the session identifier, creation time, and last accessed time
 * <li>Bind objects to sessions, allowing user information to persist
 * across multiple user connections
 * </ul>
 * <p/>
 * <p>When an application stores an object in or removes an object from a
 * session, the session checks whether the object implements
 * {@link HttpSessionBindingListener}. If it does,
 * the servlet notifies the object that it has been bound to or unbound
 * from the session. Notifications are sent after the binding methods complete.
 * For session that are invalidated or expire, notifications are sent after
 * the session has been invalidated or expired.
 * <p/>
 * <p> When container migrates a session between VMs in a distributed container
 * setting, all session attributes implementing the {@link HttpSessionActivationListener}
 * interface are notified.
 * <p/>
 * <p>A servlet should be able to handle cases in which
 * the client does not choose to join a session, such as when cookies are
 * intentionally turned off. Until the client joins the session,
 * <code>isNew</code> returns <code>true</code>.  If the client chooses
 * not to join
 * the session, <code>getSession</code> will return a different session
 * on each request, and <code>isNew</code> will always return
 * <code>true</code>.
 * <p/>
 * <p>Session information is scoped only to the current web application
 * (<code>ServletContext</code>), so information stored in one context
 * will not be directly visible in another.
 *
 * @author Various
 * @see HttpSessionBindingListener
 * @see HttpSessionContext
 */

public interface HttpSession
{


    /**
     * Returns the time when this session was created, measured
     * in milliseconds since midnight January 1, 1970 GMT.
     *
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return a <code>long</code> specifying
     * when this session was created,
     * expressed in
     * milliseconds since 1/1/1970 GMT
     */

    public long getCreationTime();


    /**
     * Returns a string containing the unique identifier assigned
     * to this session. The identifier is assigned
     * by the servlet container and is implementation dependent.
     *
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return a string specifying the identifier
     * assigned to this session
     */

    public String getId();


    /**
     * Returns the last time the client sent a request associated with
     * this session, as the number of milliseconds since midnight
     * January 1, 1970 GMT, and marked by the time the container received the request.
     * <p/>
     * <p>Actions that your application takes, such as getting or setting
     * a value associated with the session, do not affect the access
     * time.
     *
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return a <code>long</code>
     * representing the last time
     * the client sent a request associated
     * with this session, expressed in
     * milliseconds since 1/1/1970 GMT
     */

    public long getLastAccessedTime();


    /**
     * Returns the ServletContext to which this session belongs.
     *
     * @return The ServletContext object for the web application
     * @since 2.3
     */

    public ServletContext getServletContext();

    /**
     * Returns the maximum time interval, in seconds, that
     * the servlet container will keep this session open between
     * client accesses. After this interval, the servlet container
     * will invalidate the session.  The maximum time interval can be set
     * with the <code>setMaxInactiveInterval</code> method.
     * A negative time indicates the session should never timeout.
     *
     * @return an integer specifying the number of
     * seconds this session remains open
     * between client requests
     * @see        #setMaxInactiveInterval
     */

    public int getMaxInactiveInterval();

    /**
     * Specifies the time, in seconds, between client requests before the
     * servlet container will invalidate this session.  A negative time
     * indicates the session should never timeout.
     *
     * @param interval An integer specifying the number
     *                 of seconds
     */

    public void setMaxInactiveInterval(int interval);

    /**
     * @deprecated As of Version 2.1, this method is
     * deprecated and has no replacement.
     * It will be removed in a future
     * version of the Java Servlet API.
     */

    public HttpSessionContext getSessionContext();


    /**
     * Returns the object bound with the specified name in this session, or
     * <code>null</code> if no object is bound under the name.
     *
     * @param name a string specifying the name of the object
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return the object with the specified name
     */

    public Object getAttribute(String name);


    /**
     * @param name a string specifying the name of the object
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return the object with the specified name
     * @deprecated As of Version 2.2, this method is
     * replaced by {@link #getAttribute}.
     */

    public Object getValue(String name);


    /**
     * Returns an <code>Enumeration</code> of <code>String</code> objects
     * containing the names of all the objects bound to this session.
     *
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return an <code>Enumeration</code> of
     * <code>String</code> objects specifying the
     * names of all the objects bound to
     * this session
     */

    public Enumeration getAttributeNames();


    /**
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @return an array of <code>String</code>
     * objects specifying the
     * names of all the objects bound to
     * this session
     * @deprecated As of Version 2.2, this method is
     * replaced by {@link #getAttributeNames}
     */

    public String[] getValueNames();


    /**
     * Binds an object to this session, using the name specified.
     * If an object of the same name is already bound to the session,
     * the object is replaced.
     * <p/>
     * <p>After this method executes, and if the new object
     * implements <code>HttpSessionBindingListener</code>,
     * the container calls
     * <code>HttpSessionBindingListener.valueBound</code>. The container then
     * notifies any <code>HttpSessionAttributeListener</code>s in the web
     * application.
     * <p/>
     * <p>If an object was already bound to this session of this name
     * that implements <code>HttpSessionBindingListener</code>, its
     * <code>HttpSessionBindingListener.valueUnbound</code> method is called.
     * <p/>
     * <p>If the value passed in is null, this has the same effect as calling
     * <code>removeAttribute()</code>.
     *
     * @param name  the name to which the object is bound;
     *              cannot be null
     * @param value the object to be bound
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     */

    public void setAttribute(String name, Object value);


    /**
     * @param name  the name to which the object is bound;
     *              cannot be null
     * @param value the object to be bound; cannot be null
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @deprecated As of Version 2.2, this method is
     * replaced by {@link #setAttribute}
     */

    public void putValue(String name, Object value);


    /**
     * Removes the object bound with the specified name from
     * this session. If the session does not have an object
     * bound with the specified name, this method does nothing.
     * <p/>
     * <p>After this method executes, and if the object
     * implements <code>HttpSessionBindingListener</code>,
     * the container calls
     * <code>HttpSessionBindingListener.valueUnbound</code>. The container
     * then notifies any <code>HttpSessionAttributeListener</code>s in the web
     * application.
     *
     * @param name the name of the object to
     *             remove from this session
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     */

    public void removeAttribute(String name);


    /**
     * @param name the name of the object to
     *             remove from this session
     * @throws IllegalStateException if this method is called on an
     *                               invalidated session
     * @deprecated As of Version 2.2, this method is
     * replaced by {@link #removeAttribute}
     */

    public void removeValue(String name);


    /**
     * Invalidates this session then unbinds any objects bound
     * to it.
     *
     * @throws IllegalStateException if this method is called on an
     *                               already invalidated session
     */

    public void invalidate();


    /**
     * Returns <code>true</code> if the client does not yet know about the
     * session or if the client chooses not to join the session.  For
     * example, if the server used only cookie-based sessions, and
     * the client had disabled the use of cookies, then a session would
     * be new on each request.
     *
     * @return <code>true</code> if the
     * server has created a session,
     * but the client has not yet joined
     * @throws IllegalStateException if this method is called on an
     *                               already invalidated session
     */

    public boolean isNew();


}

