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

package org.apache.catalina.ha.authenticator;

import org.apache.catalina.Session;
import org.apache.catalina.ha.ClusterListener;
import org.apache.catalina.ha.ClusterManager;
import org.apache.catalina.ha.ClusterMessage;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

/**
 * Receive replicated SingleSignOnMessage form other cluster node.
 *
 * @author Fabien Carrion
 */
public class ClusterSingleSignOnListener extends ClusterListener
{

    /**
     * The descriptive information about this implementation.
     */
    protected static final String info = "org.apache.catalina.session.ClusterSingleSignOnListener/1.0";
    private static final Log log =
            LogFactory.getLog(ClusterSingleSignOnListener.class);

    // ------------------------------------------------------------- Properties
    private ClusterSingleSignOn clusterSSO = null;


    //--Constructor---------------------------------------------

    public ClusterSingleSignOnListener()
    {
    }

    //--Logic---------------------------------------------------

    /**
     * Return descriptive information about this implementation.
     */
    public String getInfo()
    {

        return (info);

    }

    public ClusterSingleSignOn getClusterSSO()
    {

        return clusterSSO;

    }

    public void setClusterSSO(ClusterSingleSignOn clusterSSO)
    {

        this.clusterSSO = clusterSSO;

    }


    /**
     * Callback from the cluster, when a message is received, The cluster will
     * broadcast it invoking the messageReceived on the receiver.
     *
     * @param myobj ClusterMessage - the message received from the cluster
     */
    public void messageReceived(ClusterMessage myobj)
    {
        if (myobj != null && myobj instanceof SingleSignOnMessage)
        {
            SingleSignOnMessage msg = (SingleSignOnMessage) myobj;
            int action = msg.getAction();
            Session session = null;
            Principal principal = null;

            if (log.isDebugEnabled())
                log.debug("SingleSignOnMessage Received with action "
                        + msg.getAction());

            switch (action)
            {
                case SingleSignOnMessage.ADD_SESSION:
                    session = getSession(msg.getSessionId(),
                            msg.getContextName());
                    if (session != null)
                        clusterSSO.associateLocal(msg.getSsoId(), session);
                    break;
                case SingleSignOnMessage.DEREGISTER_SESSION:
                    session = getSession(msg.getSessionId(),
                            msg.getContextName());
                    if (session != null)
                        clusterSSO.deregisterLocal(msg.getSsoId(), session);
                    break;
                case SingleSignOnMessage.LOGOUT_SESSION:
                    clusterSSO.deregisterLocal(msg.getSsoId());
                    break;
                case SingleSignOnMessage.REGISTER_SESSION:
                    if (msg.getPrincipal() != null)
                    {
                        principal = msg.getPrincipal().getPrincipal(clusterSSO.getContainer().getRealm());
                    }
                    clusterSSO.registerLocal(msg.getSsoId(), principal, msg.getAuthType(),
                            msg.getUsername(), msg.getPassword());
                    break;
                case SingleSignOnMessage.UPDATE_SESSION:
                    if (msg.getPrincipal() != null)
                    {
                        principal = msg.getPrincipal().getPrincipal(clusterSSO.getContainer().getRealm());
                    }
                    clusterSSO.updateLocal(msg.getSsoId(), principal, msg.getAuthType(),
                            msg.getUsername(), msg.getPassword());
                    break;
                case SingleSignOnMessage.REMOVE_SESSION:
                    session = getSession(msg.getSessionId(),
                            msg.getContextName());
                    if (session != null)
                        clusterSSO.removeSessionLocal(msg.getSsoId(), session);
                    break;
            }
        }
    }

    /**
     * Accept only SingleSignOnMessage
     *
     * @param msg ClusterMessage
     * @return boolean - returns true to indicate that messageReceived should be
     * invoked. If false is returned, the messageReceived method will
     * not be invoked.
     */
    public boolean accept(ClusterMessage msg)
    {
        return (msg instanceof SingleSignOnMessage);
    }


    private Session getSession(String sessionId, String ctxname)
    {

        Map managers = clusterSSO.getCluster().getManagers();
        Session session = null;

        if (ctxname == null)
        {
            java.util.Iterator i = managers.keySet().iterator();
            while (i.hasNext())
            {
                String key = (String) i.next();
                ClusterManager mgr = (ClusterManager) managers.get(key);
                if (mgr != null)
                {
                    try
                    {
                        session = mgr.findSession(sessionId);
                    }
                    catch (IOException io)
                    {
                        log.error("Session doesn't exist:" + io);
                    }
                    return session;
                } else
                {
                    //this happens a lot before the system has started
                    // up
                    if (log.isDebugEnabled())
                        log.debug("Context manager doesn't exist:"
                                + key);
                }
            }
        } else
        {
            ClusterManager mgr = (ClusterManager) managers.get(ctxname);
            if (mgr != null)
            {
                try
                {
                    session = mgr.findSession(sessionId);
                }
                catch (IOException io)
                {
                    log.error("Session doesn't exist:" + io);
                }
                return session;
            } else if (log.isErrorEnabled())
                log.error("Context manager doesn't exist:" + ctxname);
        }

        return null;
    }
}

