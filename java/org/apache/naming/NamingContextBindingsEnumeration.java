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


package org.apache.naming;

import javax.naming.*;
import java.util.Iterator;

/**
 * Naming enumeration implementation.
 *
 * @author Remy Maucherat
 */

public class NamingContextBindingsEnumeration
        implements NamingEnumeration
{


    // ----------------------------------------------------------- Constructors


    /**
     * Underlying enumeration.
     */
    protected Iterator iterator;

    // -------------------------------------------------------------- Variables
    /**
     * The context for which this enumeration is being generated.
     */
    private Context ctx;


    public NamingContextBindingsEnumeration(Iterator entries, Context ctx)
    {
        iterator = entries;
        this.ctx = ctx;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Retrieves the next element in the enumeration.
     */
    public Object next()
            throws NamingException
    {
        return nextElementInternal();
    }


    /**
     * Determines whether there are any more elements in the enumeration.
     */
    public boolean hasMore()
            throws NamingException
    {
        return iterator.hasNext();
    }


    /**
     * Closes this enumeration.
     */
    public void close()
            throws NamingException
    {
    }


    public boolean hasMoreElements()
    {
        return iterator.hasNext();
    }


    public Object nextElement()
    {
        try
        {
            return nextElementInternal();
        }
        catch (NamingException e)
        {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Object nextElementInternal() throws NamingException
    {
        NamingEntry entry = (NamingEntry) iterator.next();

        // If the entry is a reference, resolve it
        if (entry.type == NamingEntry.REFERENCE
                || entry.type == NamingEntry.LINK_REF)
        {
            try
            {
                // A lookup will resolve the entry
                ctx.lookup(new CompositeName(entry.name));
            }
            catch (NamingException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                NamingException ne = new NamingException(e.getMessage());
                ne.initCause(e);
                throw ne;
            }
        }

        return new Binding(entry.name, entry.value.getClass().getName(),
                entry.value, true);
    }


}

