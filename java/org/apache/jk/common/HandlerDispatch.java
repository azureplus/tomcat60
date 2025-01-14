/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jk.common;

import org.apache.jk.core.JkHandler;
import org.apache.jk.core.Msg;
import org.apache.jk.core.MsgContext;

import java.io.IOException;


/**
 * Dispatch based on the message type. ( XXX make it more generic,
 * now it's specific to ajp13 ).
 *
 * @author Costin Manolache
 */
public class HandlerDispatch extends JkHandler
{
    static final int MAX_HANDLERS = 32;
    static final int RESERVED = 16;  // reserved names, backward compat
    private static org.apache.juli.logging.Log log =
            org.apache.juli.logging.LogFactory.getLog(HandlerDispatch.class);
    JkHandler handlers[] = new JkHandler[MAX_HANDLERS];
    String handlerNames[] = new String[MAX_HANDLERS];
    int currentId = RESERVED;
    public HandlerDispatch()
    {
    }

    public void init()
    {
    }

    public int registerMessageType(int id, String name, JkHandler h,
                                   String sig[])
    {
        if (log.isDebugEnabled())
            log.debug("Register message " + id + " " + h.getName() +
                    " " + h.getClass().getName());
        if (id < 0)
        {
            // try to find it by name
            for (int i = 0; i < handlerNames.length; i++)
            {
                if (handlerNames[i] == null) continue;
                if (name.equals(handlerNames[i]))
                    return i;
            }
            handlers[currentId] = h;
            handlerNames[currentId] = name;
            currentId++;
            return currentId;
        }
        handlers[id] = h;
        handlerNames[currentId] = name;
        return id;
    }


    // -------------------- Incoming message --------------------

    public int invoke(Msg msg, MsgContext ep)
            throws IOException
    {
        int type = msg.peekByte();
        ep.setType(type);

        if (type > handlers.length ||
                handlers[type] == null)
        {
            if (log.isDebugEnabled())
                log.debug("Invalid handler " + type);
            return ERROR;
        }

        if (log.isDebugEnabled())
            log.debug("Received " + type + " " + handlers[type].getName());

        JkHandler handler = handlers[type];

        return handler.invoke(msg, ep);
    }

}
