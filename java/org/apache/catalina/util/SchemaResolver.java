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
package org.apache.catalina.util;


import org.apache.tomcat.util.digester.Digester;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.util.HashMap;

/**
 * This class implements a local SAX's <code>EntityResolver</code>. All
 * DTDs and schemas used to validate the web.xml file will re-directed
 * to a local file stored in the servlet-api.jar and jsp-api.jar.
 *
 * @author Jean-Francois Arcand
 */
public class SchemaResolver implements EntityResolver
{

    /**
     * The disgester instance for which this class is the entity resolver.
     */
    protected Digester digester;


    /**
     * The URLs of dtds and schemas that have been registered, keyed by the
     * public identifier that corresponds.
     */
    protected HashMap entityValidator = new HashMap();


    /**
     * The public identifier of the DTD we are currently parsing under
     * (if any).
     */
    protected String publicId = null;


    /**
     * Extension to make the difference between DTD and Schema.
     */
    protected String schemaExtension = "xsd";


    /**
     * Create a new <code>EntityResolver</code> that will redirect
     * all remote dtds and schema to a locat destination.
     *
     * @param digester The digester instance.
     */
    public SchemaResolver(Digester digester)
    {
        this.digester = digester;
    }


    /**
     * Register the specified DTD/Schema URL for the specified public
     * identifier. This must be called before the first call to
     * <code>parse()</code>.
     * <p/>
     * When adding a schema file (*.xsd), only the name of the file
     * will get added. If two schemas with the same name are added,
     * only the last one will be stored.
     *
     * @param publicId  Public identifier of the DTD to be resolved
     * @param entityURL The URL to use for reading this DTD
     */
    public void register(String publicId, String entityURL)
    {
        String key = publicId;
        if (publicId.indexOf(schemaExtension) != -1)
            key = publicId.substring(publicId.lastIndexOf('/') + 1);
        entityValidator.put(key, entityURL);
    }


    /**
     * Resolve the requested external entity.
     *
     * @param publicId The public identifier of the entity being referenced
     * @param systemId The system identifier of the entity being referenced
     * @throws SAXException if a parsing exception occurs
     */
    public InputSource resolveEntity(String publicId, String systemId)
            throws SAXException
    {

        if (publicId != null)
        {
            this.publicId = publicId;
            digester.setPublicId(publicId);
        }

        // Has this system identifier been registered?
        String entityURL = null;
        if (publicId != null)
        {
            entityURL = (String) entityValidator.get(publicId);
        }

        // Redirect the schema location to a local destination
        String key = null;
        if (entityURL == null && systemId != null)
        {
            key = systemId.substring(systemId.lastIndexOf('/') + 1);
            entityURL = (String) entityValidator.get(key);
        }

        if (entityURL == null)
        {
            return (null);
        }

        try
        {
            return (new InputSource(entityURL));
        }
        catch (Exception e)
        {
            throw new SAXException(e);
        }

    }

}
