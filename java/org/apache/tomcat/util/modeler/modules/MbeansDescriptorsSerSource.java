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
package org.apache.tomcat.util.modeler.modules;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.modeler.ManagedBean;
import org.apache.tomcat.util.modeler.Registry;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


public class MbeansDescriptorsSerSource extends ModelerSource
{
    private static Log log = LogFactory.getLog(MbeansDescriptorsSerSource.class);
    Registry registry;
    String location;
    String type;
    Object source;
    List mbeans = new ArrayList();

    public void setRegistry(Registry reg)
    {
        this.registry = reg;
    }

    public void setLocation(String loc)
    {
        this.location = loc;
    }

    /**
     * Used if a single component is loaded
     *
     * @param type
     */
    public void setType(String type)
    {
        this.type = type;
    }

    public void setSource(Object source)
    {
        this.source = source;
    }

    public List loadDescriptors(Registry registry, String location,
                                String type, Object source)
            throws Exception
    {
        setRegistry(registry);
        setLocation(location);
        setType(type);
        setSource(source);
        execute();
        return mbeans;
    }

    public void execute() throws Exception
    {
        if (registry == null) registry = Registry.getRegistry();
        long t1 = System.currentTimeMillis();
        try
        {
            InputStream stream = null;
            if (source instanceof URL)
            {
                stream = ((URL) source).openStream();
            }
            if (source instanceof InputStream)
            {
                stream = (InputStream) source;
            }
            if (stream == null)
            {
                throw new Exception("Can't process " + source);
            }
            ObjectInputStream ois = new ObjectInputStream(stream);
            Thread.currentThread().setContextClassLoader(ManagedBean.class.getClassLoader());
            Object obj = ois.readObject();
            //log.info("Reading " + obj);
            ManagedBean beans[] = (ManagedBean[]) obj;
            // after all are read without error
            for (int i = 0; i < beans.length; i++)
            {
                mbeans.add(beans[i]);
            }

        }
        catch (Exception ex)
        {
            log.error("Error reading descriptors " + source + " " + ex.toString(),
                    ex);
            throw ex;
        }
        long t2 = System.currentTimeMillis();
        log.info("Reading descriptors ( ser ) " + (t2 - t1));
    }
}
