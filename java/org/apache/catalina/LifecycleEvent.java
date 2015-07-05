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


package org.apache.catalina;


import java.util.EventObject;


/**
 * General event for notifying listeners of significant changes on a component
 * that implements the Lifecycle interface.  In particular, this will be useful
 * on Containers, where these events replace the ContextInterceptor concept in
 * Tomcat 3.x.
 *
 * @author Craig R. McClanahan
 */

public final class LifecycleEvent
        extends EventObject
{


    // ----------------------------------------------------------- Constructors


    /**
     * The event data associated with this event.
     */
    private Object data = null;
    /**
     * The Lifecycle on which this event occurred.
     */
    private Lifecycle lifecycle = null;


    // ----------------------------------------------------- Instance Variables
    /**
     * The event type this instance represents.
     */
    private String type = null;


    /**
     * Construct a new LifecycleEvent with the specified parameters.
     *
     * @param lifecycle Component on which this event occurred
     * @param type      Event type (required)
     */
    public LifecycleEvent(Lifecycle lifecycle, String type)
    {

        this(lifecycle, type, null);

    }


    /**
     * Construct a new LifecycleEvent with the specified parameters.
     *
     * @param lifecycle Component on which this event occurred
     * @param type      Event type (required)
     * @param data      Event data (if any)
     */
    public LifecycleEvent(Lifecycle lifecycle, String type, Object data)
    {

        super(lifecycle);
        this.lifecycle = lifecycle;
        this.type = type;
        this.data = data;

    }


    // ------------------------------------------------------------- Properties

    /**
     * Return the event data of this event.
     */
    public Object getData()
    {

        return (this.data);

    }


    /**
     * Return the Lifecycle on which this event occurred.
     */
    public Lifecycle getLifecycle()
    {

        return (this.lifecycle);

    }


    /**
     * Return the event type of this event.
     */
    public String getType()
    {

        return (this.type);

    }


}
