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

package org.apache.naming.resources;

import org.apache.naming.JndiPermission;
import org.apache.tomcat.util.http.FastHttpDateFormat;

import javax.naming.NameClassPair;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;
import java.util.*;

/**
 * Connection to a JNDI directory context.
 * <p/>
 * Note: All the object attribute names are the WebDAV names, not the HTTP
 * names, so this class overrides some methods from URLConnection to do the
 * queries using the right names. Content handler is also not used; the
 * content is directly returned.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class DirContextURLConnection
        extends URLConnection
{


    // ----------------------------------------------------------- Constructors


    /**
     * Directory context.
     */
    protected DirContext context;


    // ----------------------------------------------------- Instance Variables
    /**
     * Associated resource.
     */
    protected Resource resource;
    /**
     * Associated DirContext.
     */
    protected DirContext collection;
    /**
     * Other unknown object.
     */
    protected Object object;
    /**
     * Attributes.
     */
    protected Attributes attributes;
    /**
     * Date.
     */
    protected long date;
    /**
     * Permission
     */
    protected Permission permission;


    public DirContextURLConnection(DirContext context, URL url)
    {
        super(url);
        if (context == null)
            throw new IllegalArgumentException
                    ("Directory context can't be null");
        if (org.apache.naming.Constants.IS_SECURITY_ENABLED)
        {
            this.permission = new JndiPermission(url.toString());
        }
        this.context = context;
    }


    // ------------------------------------------------------------- Properties

    /**
     * Connect to the DirContext, and retrive the bound object, as well as
     * its attributes. If no object is bound with the name specified in the
     * URL, then an IOException is thrown.
     *
     * @throws IOException Object not found
     */
    public void connect()
            throws IOException
    {

        if (!connected)
        {

            try
            {
                date = System.currentTimeMillis();
                String path = getURL().getFile();
                if (context instanceof ProxyDirContext)
                {
                    ProxyDirContext proxyDirContext =
                            (ProxyDirContext) context;
                    String hostName = proxyDirContext.getHostName();
                    String contextName = proxyDirContext.getContextName();
                    if (hostName != null)
                    {
                        if (!path.startsWith("/" + hostName + "/"))
                            return;
                        path = path.substring(hostName.length() + 1);
                    }
                    if (contextName != null)
                    {
                        if (!path.startsWith(contextName + "/"))
                        {
                            return;
                        } else
                        {
                            path = path.substring(contextName.length());
                        }
                    }
                }
                object = context.lookup(path);
                attributes = context.getAttributes(path);
                if (object instanceof Resource)
                    resource = (Resource) object;
                if (object instanceof DirContext)
                    collection = (DirContext) object;
            }
            catch (NamingException e)
            {
                // Object not found
            }

            connected = true;

        }

    }


    /**
     * Return the content length value.
     */
    public int getContentLength()
    {
        return getHeaderFieldInt(ResourceAttributes.CONTENT_LENGTH, -1);
    }


    /**
     * Return the content type value.
     */
    public String getContentType()
    {
        return getHeaderField(ResourceAttributes.CONTENT_TYPE);
    }


    /**
     * Return the last modified date.
     */
    public long getDate()
    {
        return date;
    }


    /**
     * Return the last modified date.
     */
    public long getLastModified()
    {

        if (!connected)
        {
            // Try to connect (silently)
            try
            {
                connect();
            }
            catch (IOException e)
            {
            }
        }

        if (attributes == null)
            return 0;

        Attribute lastModified =
                attributes.get(ResourceAttributes.LAST_MODIFIED);
        if (lastModified != null)
        {
            try
            {
                Date lmDate = (Date) lastModified.get();
                return lmDate.getTime();
            }
            catch (Exception e)
            {
            }
        }

        return 0;
    }


    protected String getHeaderValueAsString(Object headerValue)
    {
        if (headerValue == null) return null;
        if (headerValue instanceof Date)
        {
            // return date strings (ie Last-Modified) in HTTP format, rather
            // than Java format
            return FastHttpDateFormat.formatDate(
                    ((Date) headerValue).getTime(), null);
        }
        return headerValue.toString();
    }


    /**
     * Returns an unmodifiable Map of the header fields.
     */
    public Map getHeaderFields()
    {

        if (!connected)
        {
            // Try to connect (silently)
            try
            {
                connect();
            }
            catch (IOException e)
            {
            }
        }

        if (attributes == null)
            return (Collections.EMPTY_MAP);

        HashMap headerFields = new HashMap(attributes.size());
        NamingEnumeration attributeEnum = attributes.getIDs();
        try
        {
            while (attributeEnum.hasMore())
            {
                String attributeID = (String) attributeEnum.next();
                Attribute attribute = attributes.get(attributeID);
                if (attribute == null) continue;
                ArrayList attributeValueList = new ArrayList(attribute.size());
                NamingEnumeration attributeValues = attribute.getAll();
                while (attributeValues.hasMore())
                {
                    Object attrValue = attributeValues.next();
                    attributeValueList.add(getHeaderValueAsString(attrValue));
                }
                attributeValueList.trimToSize(); // should be a no-op if attribute.size() didn't lie
                headerFields.put(attributeID, Collections.unmodifiableList(attributeValueList));
            }
        }
        catch (NamingException ne)
        {
            // Shouldn't happen
        }

        return Collections.unmodifiableMap(headerFields);

    }


    /**
     * Returns the name of the specified header field.
     */
    public String getHeaderField(String name)
    {

        if (!connected)
        {
            // Try to connect (silently)
            try
            {
                connect();
            }
            catch (IOException e)
            {
            }
        }

        if (attributes == null)
            return (null);

        NamingEnumeration attributeEnum = attributes.getIDs();
        try
        {
            while (attributeEnum.hasMore())
            {
                String attributeID = (String) attributeEnum.next();
                if (attributeID.equalsIgnoreCase(name))
                {
                    Attribute attribute = attributes.get(attributeID);
                    if (attribute == null) return null;
                    Object attrValue = attribute.get(attribute.size() - 1);
                    return getHeaderValueAsString(attrValue);
                }
            }
        }
        catch (NamingException ne)
        {
            // Shouldn't happen
        }

        return (null);

    }


    /**
     * Get object content.
     */
    public Object getContent()
            throws IOException
    {

        if (!connected)
            connect();

        if (resource != null)
            return getInputStream();
        if (collection != null)
            return collection;
        if (object != null)
            return object;

        throw new FileNotFoundException(
                getURL() == null ? "null" : getURL().toString());

    }


    /**
     * Get object content.
     */
    public Object getContent(Class[] classes)
            throws IOException
    {

        Object object = getContent();

        for (int i = 0; i < classes.length; i++)
        {
            if (classes[i].isInstance(object))
                return object;
        }

        return null;

    }


    /**
     * Get input stream.
     */
    public InputStream getInputStream()
            throws IOException
    {

        if (!connected)
            connect();

        if (resource == null)
        {
            throw new FileNotFoundException(
                    getURL() == null ? "null" : getURL().toString());
        } else
        {
            // Reopen resource
            try
            {
                resource = (Resource) context.lookup(getURL().getFile());
            }
            catch (NamingException e)
            {
            }
        }

        return (resource.streamContent());

    }


    /**
     * Get the Permission for this URL
     */
    public Permission getPermission()
    {

        return permission;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * List children of this collection. The names given are relative to this
     * URI's path. The full uri of the children is then : path + "/" + name.
     */
    public Enumeration list()
            throws IOException
    {

        if (!connected)
        {
            connect();
        }

        if ((resource == null) && (collection == null))
        {
            throw new FileNotFoundException(
                    getURL() == null ? "null" : getURL().toString());
        }

        Vector result = new Vector();

        if (collection != null)
        {
            try
            {
                NamingEnumeration enumeration = context.list(getURL().getFile());
                while (enumeration.hasMoreElements())
                {
                    NameClassPair ncp = (NameClassPair) enumeration.nextElement();
                    result.addElement(ncp.getName());
                }
            }
            catch (NamingException e)
            {
                // Unexpected exception
                throw new FileNotFoundException(
                        getURL() == null ? "null" : getURL().toString());
            }
        }

        return result.elements();

    }


}
