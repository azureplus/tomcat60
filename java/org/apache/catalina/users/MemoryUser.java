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


package org.apache.catalina.users;


import org.apache.catalina.Group;
import org.apache.catalina.Role;
import org.apache.catalina.UserDatabase;
import org.apache.catalina.util.RequestUtil;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * <p>Concrete implementation of {@link org.apache.catalina.User} for the
 * {@link MemoryUserDatabase} implementation of {@link UserDatabase}.</p>
 *
 * @author Craig R. McClanahan
 * @since 4.1
 */

public class MemoryUser extends AbstractUser
{


    // ----------------------------------------------------------- Constructors


    /**
     * The {@link MemoryUserDatabase} that owns this user.
     */
    protected MemoryUserDatabase database = null;


    // ----------------------------------------------------- Instance Variables
    /**
     * The set of {@link Group}s that this user is a member of.
     */
    protected ArrayList groups = new ArrayList();
    /**
     * The set of {@link Role}s associated with this user.
     */
    protected ArrayList roles = new ArrayList();


    /**
     * Package-private constructor used by the factory method in
     * {@link MemoryUserDatabase}.
     *
     * @param database The {@link MemoryUserDatabase} that owns this user
     * @param username Logon username of the new user
     * @param password Logon password of the new user
     * @param fullName Full name of the new user
     */
    MemoryUser(MemoryUserDatabase database, String username,
               String password, String fullName)
    {

        super();
        this.database = database;
        setUsername(username);
        setPassword(password);
        setFullName(fullName);

    }


    // ------------------------------------------------------------- Properties

    /**
     * Return the set of {@link Group}s to which this user belongs.
     */
    public Iterator getGroups()
    {

        synchronized (groups)
        {
            return (groups.iterator());
        }

    }


    /**
     * Return the set of {@link Role}s assigned specifically to this user.
     */
    public Iterator getRoles()
    {

        synchronized (roles)
        {
            return (roles.iterator());
        }

    }


    /**
     * Return the {@link UserDatabase} within which this User is defined.
     */
    public UserDatabase getUserDatabase()
    {

        return (this.database);

    }


    // --------------------------------------------------------- Public Methods


    /**
     * Add a new {@link Group} to those this user belongs to.
     *
     * @param group The new group
     */
    public void addGroup(Group group)
    {

        synchronized (groups)
        {
            if (!groups.contains(group))
            {
                groups.add(group);
            }
        }

    }


    /**
     * Add a new {@link Role} to those assigned specifically to this user.
     *
     * @param role The new role
     */
    public void addRole(Role role)
    {

        synchronized (roles)
        {
            if (!roles.contains(role))
            {
                roles.add(role);
            }
        }

    }


    /**
     * Is this user in the specified group?
     *
     * @param group The group to check
     */
    public boolean isInGroup(Group group)
    {

        synchronized (groups)
        {
            return (groups.contains(group));
        }

    }


    /**
     * Is this user specifically assigned the specified {@link Role}?  This
     * method does <strong>NOT</strong> check for roles inherited based on
     * {@link Group} membership.
     *
     * @param role The role to check
     */
    public boolean isInRole(Role role)
    {

        synchronized (roles)
        {
            return (roles.contains(role));
        }

    }


    /**
     * Remove a {@link Group} from those this user belongs to.
     *
     * @param group The old group
     */
    public void removeGroup(Group group)
    {

        synchronized (groups)
        {
            groups.remove(group);
        }

    }


    /**
     * Remove all {@link Group}s from those this user belongs to.
     */
    public void removeGroups()
    {

        synchronized (groups)
        {
            groups.clear();
        }

    }


    /**
     * Remove a {@link Role} from those assigned to this user.
     *
     * @param role The old role
     */
    public void removeRole(Role role)
    {

        synchronized (roles)
        {
            roles.remove(role);
        }

    }


    /**
     * Remove all {@link Role}s from those assigned to this user.
     */
    public void removeRoles()
    {

        synchronized (roles)
        {
            roles.clear();
        }

    }


    /**
     * <p>Return a String representation of this user in XML format.</p>
     * <p/>
     * <p><strong>IMPLEMENTATION NOTE</strong> - For backwards compatibility,
     * the reader that processes this entry will accept either
     * <code>username</code> or </code>name</code> for the username
     * property.</p>
     */
    public String toXml()
    {

        StringBuffer sb = new StringBuffer("<user username=\"");
        sb.append(RequestUtil.filter(username));
        sb.append("\" password=\"");
        sb.append(RequestUtil.filter(password));
        sb.append("\"");
        if (fullName != null)
        {
            sb.append(" fullName=\"");
            sb.append(RequestUtil.filter(fullName));
            sb.append("\"");
        }
        synchronized (groups)
        {
            if (groups.size() > 0)
            {
                sb.append(" groups=\"");
                int n = 0;
                Iterator values = groups.iterator();
                while (values.hasNext())
                {
                    if (n > 0)
                    {
                        sb.append(',');
                    }
                    n++;
                    sb.append(RequestUtil.filter(((Group) values.next()).getGroupname()));
                }
                sb.append("\"");
            }
        }
        synchronized (roles)
        {
            if (roles.size() > 0)
            {
                sb.append(" roles=\"");
                int n = 0;
                Iterator values = roles.iterator();
                while (values.hasNext())
                {
                    if (n > 0)
                    {
                        sb.append(',');
                    }
                    n++;
                    sb.append(RequestUtil.filter(((Role) values.next()).getRolename()));
                }
                sb.append("\"");
            }
        }
        sb.append("/>");
        return (sb.toString());

    }

    /**
     * <p>Return a String representation of this user.</p>
     */
    @Override
    public String toString()
    {

        StringBuilder sb = new StringBuilder("User username=\"");
        sb.append(RequestUtil.filter(username));
        sb.append("\"");
        if (fullName != null)
        {
            sb.append(", fullName=\"");
            sb.append(RequestUtil.filter(fullName));
            sb.append("\"");
        }
        synchronized (groups)
        {
            if (groups.size() > 0)
            {
                sb.append(", groups=\"");
                int n = 0;
                Iterator<Group> values = groups.iterator();
                while (values.hasNext())
                {
                    if (n > 0)
                    {
                        sb.append(',');
                    }
                    n++;
                    sb.append(RequestUtil.filter(values.next().getGroupname()));
                }
                sb.append("\"");
            }
        }
        synchronized (roles)
        {
            if (roles.size() > 0)
            {
                sb.append(", roles=\"");
                int n = 0;
                Iterator<Role> values = roles.iterator();
                while (values.hasNext())
                {
                    if (n > 0)
                    {
                        sb.append(',');
                    }
                    n++;
                    sb.append(RequestUtil.filter(values.next().getRolename()));
                }
                sb.append("\"");
            }
        }
        return (sb.toString());
    }


}
