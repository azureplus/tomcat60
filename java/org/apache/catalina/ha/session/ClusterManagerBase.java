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

package org.apache.catalina.ha.session;

import org.apache.catalina.Container;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.Loader;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.tribes.io.ReplicationStream;

import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * @author Filip Hanik
 */

public abstract class ClusterManagerBase extends ManagerBase implements Lifecycle,
        PropertyChangeListener,
        ClusterManager
{


    /**
     * The pattern used for including session attributes to
     * replication, e.g. <code>^(userName|sessionHistory)$</code>.
     * If not set, all session attributes will be eligible for replication.
     */
    private String sessionAttributeFilter = null;

    /**
     * The compiled pattern used for including session attributes to
     * replication, e.g. <code>^(userName|sessionHistory)$</code>.
     * If not set, all session attributes will be eligible for replication.
     */
    private Pattern sessionAttributePattern = null;

    public static ClassLoader[] getClassLoaders(Container container)
    {
        Loader loader = null;
        ClassLoader classLoader = null;
        if (container != null) loader = container.getLoader();
        if (loader != null) classLoader = loader.getClassLoader();
        else classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == Thread.currentThread().getContextClassLoader())
        {
            return new ClassLoader[]{classLoader};
        } else
        {
            return new ClassLoader[]{classLoader, Thread.currentThread().getContextClassLoader()};
        }
    }

    /**
     * Return the string pattern used for including session attributes
     * to replication.
     *
     * @return the sessionAttributeFilter
     */
    public String getSessionAttributeFilter()
    {
        return sessionAttributeFilter;
    }

    /**
     * Set the pattern used for including session attributes to replication.
     * If not set, all session attributes will be eligible for replication.
     * <p>
     * E.g. <code>^(userName|sessionHistory)$</code>
     * </p>
     *
     * @param sessionAttributeFilter the filter name pattern to set
     */
    public void setSessionAttributeFilter(String sessionAttributeFilter)
    {
        if (sessionAttributeFilter == null
                || sessionAttributeFilter.trim().equals(""))
        {
            this.sessionAttributeFilter = null;
            sessionAttributePattern = null;
        } else
        {
            this.sessionAttributeFilter = sessionAttributeFilter;
            sessionAttributePattern = Pattern.compile(sessionAttributeFilter);
        }
    }

    /**
     * Check whether the given session attribute should be distributed
     *
     * @return true if the attribute should be distributed
     */
    public boolean willAttributeDistribute(String name)
    {
        if (sessionAttributePattern == null)
        {
            return true;
        }
        return sessionAttributePattern.matcher(name).matches();
    }

    public ClassLoader[] getClassLoaders()
    {
        return getClassLoaders(container);
    }

    /**
     * Open Stream and use correct ClassLoader (Container) Switch
     * ThreadClassLoader
     *
     * @param data
     * @return The object input stream
     * @throws IOException
     */
    public ReplicationStream getReplicationStream(byte[] data) throws IOException
    {
        return getReplicationStream(data, 0, data.length);
    }

    public ReplicationStream getReplicationStream(byte[] data, int offset, int length) throws IOException
    {
        ByteArrayInputStream fis = new ByteArrayInputStream(data, offset, length);
        return new ReplicationStream(fis, getClassLoaders());
    }


}
