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

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 *
 */
public abstract class ELContext
{

    private Locale locale;

    private Map<Class<?>, Object> map;

    private boolean resolved;

    /**
     *
     */
    public ELContext()
    {
        this.resolved = false;
    }

    public Object getContext(Class key)
    {
        if (this.map == null)
        {
            return null;
        }
        return this.map.get(key);
    }

    public void putContext(Class key, Object contextObject) throws NullPointerException
    {
        if (key == null || contextObject == null)
        {
            throw new NullPointerException();
        }

        if (this.map == null)
        {
            this.map = new HashMap<Class<?>, Object>();
        }

        this.map.put(key, contextObject);
    }

    public boolean isPropertyResolved()
    {
        return this.resolved;
    }

    public void setPropertyResolved(boolean resolved)
    {
        this.resolved = resolved;
    }

    public abstract ELResolver getELResolver();

    public abstract FunctionMapper getFunctionMapper();

    public abstract VariableMapper getVariableMapper();

    public Locale getLocale()
    {
        return this.locale;
    }

    public void setLocale(Locale locale)
    {
        this.locale = locale;
    }
}
