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

package javax.el;

import java.beans.FeatureDescriptor;
import java.util.*;

public class ListELResolver extends ELResolver
{

    private final static Class UNMODIFIABLE = Collections.unmodifiableList(
            new ArrayList()).getClass();
    private final boolean readOnly;

    public ListELResolver()
    {
        this.readOnly = false;
    }

    public ListELResolver(boolean readOnly)
    {
        this.readOnly = readOnly;
    }

    private final static int coerce(Object property)
    {
        if (property instanceof Number)
        {
            return ((Number) property).intValue();
        }
        if (property instanceof Character)
        {
            return ((Character) property).charValue();
        }
        if (property instanceof Boolean)
        {
            return (((Boolean) property).booleanValue() ? 1 : 0);
        }
        if (property instanceof String)
        {
            return Integer.parseInt((String) property);
        }
        throw new IllegalArgumentException(property != null ? property
                .toString() : "null");
    }

    public Object getValue(ELContext context, Object base, Object property)
            throws NullPointerException, PropertyNotFoundException, ELException
    {
        if (context == null)
        {
            throw new NullPointerException();
        }

        if (base instanceof List)
        {
            context.setPropertyResolved(true);
            List list = (List) base;
            int idx = coerce(property);
            if (idx < 0 || idx >= list.size())
            {
                return null;
            }
            return list.get(idx);
        }

        return null;
    }

    public Class<?> getType(ELContext context, Object base, Object property)
            throws NullPointerException, PropertyNotFoundException, ELException
    {
        if (context == null)
        {
            throw new NullPointerException();
        }

        if (base instanceof List)
        {
            context.setPropertyResolved(true);
            List list = (List) base;
            int idx = coerce(property);
            if (idx < 0 || idx >= list.size())
            {
                throw new PropertyNotFoundException(
                        new ArrayIndexOutOfBoundsException(idx).getMessage());
            }
            return Object.class;
        }

        return null;
    }

    public void setValue(ELContext context, Object base, Object property,
                         Object value) throws NullPointerException,
            PropertyNotFoundException, PropertyNotWritableException,
            ELException
    {
        if (context == null)
        {
            throw new NullPointerException();
        }

        if (base instanceof List)
        {
            context.setPropertyResolved(true);
            List list = (List) base;

            if (this.readOnly)
            {
                throw new PropertyNotWritableException(message(context,
                        "resolverNotWriteable", new Object[]{base.getClass()
                                .getName()}));
            }

            int idx = coerce(property);
            try
            {
                list.set(idx, value);
            }
            catch (UnsupportedOperationException e)
            {
                throw new PropertyNotWritableException(e);
            }
            catch (IndexOutOfBoundsException e)
            {
                throw new PropertyNotFoundException(e);
            }
        }
    }

    public boolean isReadOnly(ELContext context, Object base, Object property)
            throws NullPointerException, PropertyNotFoundException, ELException
    {
        if (context == null)
        {
            throw new NullPointerException();
        }

        if (base instanceof List)
        {
            context.setPropertyResolved(true);
            List list = (List) base;
            int idx = coerce(property);
            if (idx < 0 || idx >= list.size())
            {
                throw new PropertyNotFoundException(
                        new ArrayIndexOutOfBoundsException(idx).getMessage());
            }
            return this.readOnly || UNMODIFIABLE.equals(list.getClass());
        }

        return this.readOnly;
    }

    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base)
    {
        if (base instanceof List)
        {
            FeatureDescriptor[] descs = new FeatureDescriptor[((List) base).size()];
            for (int i = 0; i < descs.length; i++)
            {
                descs[i] = new FeatureDescriptor();
                descs[i].setDisplayName("[" + i + "]");
                descs[i].setExpert(false);
                descs[i].setHidden(false);
                descs[i].setName("" + i);
                descs[i].setPreferred(true);
                descs[i].setValue(RESOLVABLE_AT_DESIGN_TIME, Boolean.FALSE);
                descs[i].setValue(TYPE, Integer.class);
            }
            return Arrays.asList(descs).iterator();
        }
        return null;
    }

    public Class<?> getCommonPropertyType(ELContext context, Object base)
    {
        if (base != null && base instanceof List)
        {
            return Integer.class;
        }
        return null;
    }
}
