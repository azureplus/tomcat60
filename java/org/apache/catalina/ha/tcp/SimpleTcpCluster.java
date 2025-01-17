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
package org.apache.catalina.ha.tcp;

import org.apache.catalina.*;
import org.apache.catalina.ha.*;
import org.apache.catalina.ha.session.*;
import org.apache.catalina.ha.util.IDynamicProperty;
import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelListener;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.MembershipListener;
import org.apache.catalina.tribes.group.GroupChannel;
import org.apache.catalina.tribes.group.interceptors.MessageDispatch15Interceptor;
import org.apache.catalina.tribes.group.interceptors.TcpFailureDetector;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.IntrospectionUtils;

import java.beans.PropertyChangeSupport;
import java.io.Serializable;
import java.util.*;

/**
 * A <b>Cluster </b> implementation using simple multicast. Responsible for
 * setting up a cluster and provides callers with a valid multicast
 * receiver/sender.
 * <p/>
 * FIXME remove install/remove/start/stop context dummys
 * FIXME wrote testcases
 *
 * @author Filip Hanik
 * @author Remy Maucherat
 * @author Peter Rossbach
 */
public class SimpleTcpCluster
        implements CatalinaCluster, Lifecycle, LifecycleListener, IDynamicProperty,
        MembershipListener, ChannelListener
{

    public static final String BEFORE_MEMBERREGISTER_EVENT = "before_member_register";

    // ----------------------------------------------------- Instance Variables
    public static final String AFTER_MEMBERREGISTER_EVENT = "after_member_register";
    public static final String BEFORE_MANAGERREGISTER_EVENT = "before_manager_register";
    public static final String AFTER_MANAGERREGISTER_EVENT = "after_manager_register";
    public static final String BEFORE_MANAGERUNREGISTER_EVENT = "before_manager_unregister";
    public static final String AFTER_MANAGERUNREGISTER_EVENT = "after_manager_unregister";
    public static final String BEFORE_MEMBERUNREGISTER_EVENT = "before_member_unregister";
    public static final String AFTER_MEMBERUNREGISTER_EVENT = "after_member_unregister";
    public static final String SEND_MESSAGE_FAILURE_EVENT = "send_message_failure";
    public static final String RECEIVE_MESSAGE_FAILURE_EVENT = "receive_message_failure";
    /**
     * Descriptive information about this component implementation.
     */
    protected static final String info = "SimpleTcpCluster/2.2";
    public static Log log = LogFactory.getLog(SimpleTcpCluster.class);
    /**
     * Group channel.
     */
    protected Channel channel = new GroupChannel();


    /**
     * Name for logging purpose
     */
    protected String clusterImpName = "SimpleTcpCluster";

    /**
     * The string manager for this package.
     */
    protected StringManager sm = StringManager.getManager(Constants.Package);

    /**
     * The cluster name to join
     */
    protected String clusterName;

    /**
     * call Channel.heartbeat() at container background thread
     *
     * @see org.apache.catalina.tribes.group.GroupChannel#heartbeat()
     */
    protected boolean heartbeatBackgroundEnabled = false;

    /**
     * The Container associated with this Cluster.
     */
    protected Container container = null;

    /**
     * The lifecycle event support for this component.
     */
    protected LifecycleSupport lifecycle = new LifecycleSupport(this);

    /**
     * Has this component been started?
     */
    protected boolean started = false;

    /**
     * The property change support for this component.
     */
    protected PropertyChangeSupport support = new PropertyChangeSupport(this);

    /**
     * The context name <->manager association for distributed contexts.
     */
    protected Map managers = new HashMap();

    protected ClusterManager managerTemplate = new DeltaManager();
    /**
     * Listeners of messages
     */
    protected List clusterListeners = new ArrayList();
    /**
     * has members
     */
    protected boolean hasMembers = false;
    private List valves = new ArrayList();
    private org.apache.catalina.ha.ClusterDeployer clusterDeployer;
    /**
     * Comment for <code>notifyLifecycleListenerOnFailure</code>
     */
    private boolean notifyLifecycleListenerOnFailure = false;
    /**
     * dynamic sender <code>properties</code>
     */
    private Map properties = new HashMap();
    private int channelSendOptions = Channel.SEND_OPTIONS_ASYNCHRONOUS;

    // ------------------------------------------------------------- Properties
    private int channelStartOptions = Channel.DEFAULT;

    public SimpleTcpCluster()
    {
    }

    /**
     * Return descriptive information about this Cluster implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo()
    {
        return (info);
    }

    /**
     * Return heartbeat enable flag (default false)
     *
     * @return the heartbeatBackgroundEnabled
     */
    public boolean isHeartbeatBackgroundEnabled()
    {
        return heartbeatBackgroundEnabled;
    }

    /**
     * enabled that container backgroundThread call heartbeat at channel
     *
     * @param heartbeatBackgroundEnabled the heartbeatBackgroundEnabled to set
     */
    public void setHeartbeatBackgroundEnabled(boolean heartbeatBackgroundEnabled)
    {
        this.heartbeatBackgroundEnabled = heartbeatBackgroundEnabled;
    }

    /**
     * Return the name of the cluster that this Server is currently configured
     * to operate within.
     *
     * @return The name of the cluster associated with this server
     */
    public String getClusterName()
    {
        if (clusterName == null && container != null)
            return container.getName();
        return clusterName;
    }

    /**
     * Set the name of the cluster to join, if no cluster with this name is
     * present create one.
     *
     * @param clusterName The clustername to join
     */
    public void setClusterName(String clusterName)
    {
        this.clusterName = clusterName;
    }

    /**
     * Get the Container associated with our Cluster
     *
     * @return The Container associated with our Cluster
     */
    public Container getContainer()
    {
        return (this.container);
    }

    /**
     * Set the Container associated with our Cluster
     *
     * @param container The Container to use
     */
    public void setContainer(Container container)
    {
        Container oldContainer = this.container;
        this.container = container;
        support.firePropertyChange("container", oldContainer, this.container);
    }

    /**
     * @return Returns the notifyLifecycleListenerOnFailure.
     */
    public boolean isNotifyLifecycleListenerOnFailure()
    {
        return notifyLifecycleListenerOnFailure;
    }

    /**
     * @param notifyListenerOnFailure The notifyLifecycleListenerOnFailure to set.
     */
    public void setNotifyLifecycleListenerOnFailure(
            boolean notifyListenerOnFailure)
    {
        boolean oldNotifyListenerOnFailure = this.notifyLifecycleListenerOnFailure;
        this.notifyLifecycleListenerOnFailure = notifyListenerOnFailure;
        support.firePropertyChange("notifyLifecycleListenerOnFailure",
                oldNotifyListenerOnFailure,
                this.notifyLifecycleListenerOnFailure);
    }

    /**
     * @return String
     * @deprecated use getManagerTemplate().getClass().getName() instead.
     */
    public String getManagerClassName()
    {
        return managerTemplate.getClass().getName();
    }

    /**
     * @param managerClassName String
     * @deprecated use nested &lt;Manager&gt; element inside the cluster config instead.
     */
    public void setManagerClassName(String managerClassName)
    {
        log.warn("setManagerClassName is deprecated, use nested <Manager> element inside the <Cluster> element instead, this request will be ignored.");
    }

    /**
     * Add cluster valve
     * Cluster Valves are only add to container when cluster is started!
     *
     * @param valve The new cluster Valve.
     */
    public void addValve(Valve valve)
    {
        if (valve instanceof ClusterValve && (!valves.contains(valve)))
            valves.add(valve);
    }

    /**
     * get all cluster valves
     *
     * @return current cluster valves
     */
    public Valve[] getValves()
    {
        return (Valve[]) valves.toArray(new Valve[valves.size()]);
    }

    /**
     * Get the cluster listeners associated with this cluster. If this Array has
     * no listeners registered, a zero-length array is returned.
     */
    public ClusterListener[] findClusterListeners()
    {
        if (clusterListeners.size() > 0)
        {
            ClusterListener[] listener = new ClusterListener[clusterListeners.size()];
            clusterListeners.toArray(listener);
            return listener;
        } else
            return new ClusterListener[0];

    }

    /**
     * add cluster message listener and register cluster to this listener
     *
     * @see org.apache.catalina.ha.CatalinaCluster#addClusterListener(org.apache.catalina.ha.ClusterListener)
     */
    public void addClusterListener(ClusterListener listener)
    {
        if (listener != null && !clusterListeners.contains(listener))
        {
            clusterListeners.add(listener);
            listener.setCluster(this);
        }
    }

    /**
     * remove message listener and deregister Cluster from listener
     *
     * @see org.apache.catalina.ha.CatalinaCluster#removeClusterListener(org.apache.catalina.ha.ClusterListener)
     */
    public void removeClusterListener(ClusterListener listener)
    {
        if (listener != null)
        {
            clusterListeners.remove(listener);
            listener.setCluster(null);
        }
    }

    /**
     * get current Deployer
     */
    public org.apache.catalina.ha.ClusterDeployer getClusterDeployer()
    {
        return clusterDeployer;
    }

    /**
     * set a new Deployer, must be set before cluster started!
     */
    public void setClusterDeployer(
            org.apache.catalina.ha.ClusterDeployer clusterDeployer)
    {
        this.clusterDeployer = clusterDeployer;
    }

    public boolean hasMembers()
    {
        return hasMembers;
    }

    /**
     * Get all current cluster members
     *
     * @return all members or empty array
     */
    public Member[] getMembers()
    {
        return channel.getMembers();
    }

    /**
     * Return the member that represents this node.
     *
     * @return Member
     */
    public Member getLocalMember()
    {
        return channel.getLocalMember(true);
    }

    /**
     * JMX hack to direct use at jconsole
     *
     * @param name
     * @param value
     */
    public boolean setProperty(String name, String value)
    {
        return setProperty(name, (Object) value);
    }

    /**
     * set config attributes with reflect and propagate to all managers
     *
     * @param name
     * @param value
     */
    public boolean setProperty(String name, Object value)
    {
        properties.put(name, value);
        return false;
    }

    /**
     * get current config
     *
     * @param key
     * @return The property
     */
    public Object getProperty(String key)
    {
        if (log.isTraceEnabled())
            log.trace(sm.getString("SimpleTcpCluster.getProperty", key));
        return properties.get(key);
    }

    // ------------------------------------------------------------- dynamic
    // manager property handling

    /**
     * Get all properties keys
     *
     * @return An iterator over the property names.
     */
    public Iterator getPropertyNames()
    {
        return properties.keySet().iterator();
    }

    /**
     * remove a configured property.
     *
     * @param key
     */
    public void removeProperty(String key)
    {
        properties.remove(key);
    }

    /**
     * transfer properties from cluster configuration to subelement bean.
     *
     * @param prefix
     * @param bean
     */
    protected void transferProperty(String prefix, Object bean)
    {
        if (prefix != null)
        {
            for (Iterator iter = getPropertyNames(); iter.hasNext(); )
            {
                String pkey = (String) iter.next();
                if (pkey.startsWith(prefix))
                {
                    String key = pkey.substring(prefix.length() + 1);
                    Object value = getProperty(pkey);
                    IntrospectionUtils.setProperty(bean, key, value.toString());
                }
            }
        }
    }

    /**
     * @return Returns the managers.
     */
    public Map getManagers()
    {
        return managers;
    }

    public Channel getChannel()
    {
        return channel;
    }

    public void setChannel(Channel channel)
    {
        this.channel = channel;
    }

    // --------------------------------------------------------- Public Methods

    public ClusterManager getManagerTemplate()
    {
        return managerTemplate;
    }

    public void setManagerTemplate(ClusterManager managerTemplate)
    {
        this.managerTemplate = managerTemplate;
    }

    public int getChannelSendOptions()
    {
        return channelSendOptions;
    }

    public void setChannelSendOptions(int channelSendOptions)
    {
        this.channelSendOptions = channelSendOptions;
    }

    /**
     * Create new Manager without add to cluster (comes with start the manager)
     *
     * @param name Context Name of this manager
     * @see org.apache.catalina.Cluster#createManager(java.lang.String)
     * @see #registerManager(Manager)
     * @see DeltaManager#start()
     */
    public synchronized Manager createManager(String name)
    {
        if (log.isDebugEnabled())
            log.debug("Creating ClusterManager for context " + name + " using class " + getManagerClassName());
        Manager manager = null;
        try
        {
            manager = managerTemplate.cloneFromTemplate();
            ((ClusterManager) manager).setName(name);
        }
        catch (Exception x)
        {
            log.error("Unable to clone cluster manager, defaulting to org.apache.catalina.ha.session.DeltaManager", x);
            manager = new org.apache.catalina.ha.session.DeltaManager();
        }
        finally
        {
            if (manager != null && (manager instanceof ClusterManager)) ((ClusterManager) manager).setCluster(this);
        }
        return manager;
    }

    public void registerManager(Manager manager)
    {

        if (!(manager instanceof ClusterManager))
        {
            log.warn("Manager [ " + manager + "] does not implement ClusterManager, addition to cluster has been aborted.");
            return;
        }
        ClusterManager cmanager = (ClusterManager) manager;
        cmanager.setDistributable(true);
        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_MANAGERREGISTER_EVENT, manager);
        String clusterName = getManagerName(cmanager.getName(), manager);
        cmanager.setName(clusterName);
        cmanager.setCluster(this);
        cmanager.setDefaultMode(false);

        managers.put(clusterName, manager);
        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(AFTER_MANAGERREGISTER_EVENT, manager);
    }

    /**
     * remove an application form cluster replication bus
     *
     * @see org.apache.catalina.ha.CatalinaCluster#removeManager(Manager)
     */
    public void removeManager(Manager manager)
    {
        if (manager != null && manager instanceof ClusterManager)
        {
            ClusterManager cmgr = (ClusterManager) manager;
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(BEFORE_MANAGERUNREGISTER_EVENT, manager);
            managers.remove(getManagerName(cmgr.getName(), manager));
            cmgr.setCluster(null);
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(AFTER_MANAGERUNREGISTER_EVENT, manager);
        }
    }

    public String getManagerName(String name, Manager manager)
    {
        String clusterName = name;
        if (clusterName == null) clusterName = manager.getContainer().getName();
        if (getContainer() instanceof Engine)
        {
            Container context = manager.getContainer();
            if (context != null && context instanceof Context)
            {
                Container host = ((Context) context).getParent();
                if (host != null && host instanceof Host && clusterName != null && !(clusterName.indexOf("#") >= 0))
                    clusterName = host.getName() + "#" + clusterName;
            }
        }
        return clusterName;
    }

    /*
     * Get Manager
     * 
     * @see org.apache.catalina.ha.CatalinaCluster#getManager(java.lang.String)
     */
    public Manager getManager(String name)
    {
        return (Manager) managers.get(name);
    }

    // ------------------------------------------------------ Lifecycle Methods

    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     *
     * @see org.apache.catalina.ha.deploy.FarmWarDeployer#backgroundProcess()
     * @see org.apache.catalina.tribes.group.GroupChannel#heartbeat()
     * @see org.apache.catalina.tribes.group.GroupChannel.HeartbeatThread#run()
     */
    public void backgroundProcess()
    {
        if (clusterDeployer != null) clusterDeployer.backgroundProcess();

        //send a heartbeat through the channel        
        if (isHeartbeatBackgroundEnabled() && channel != null) channel.heartbeat();
    }

    /**
     * Add a lifecycle event listener to this component.
     *
     * @param listener The listener to add
     */
    public void addLifecycleListener(LifecycleListener listener)
    {
        lifecycle.addLifecycleListener(listener);
    }

    /**
     * Get the lifecycle listeners associated with this lifecycle. If this
     * Lifecycle has no listeners registered, a zero-length array is returned.
     */
    public LifecycleListener[] findLifecycleListeners()
    {

        return lifecycle.findLifecycleListeners();

    }

    /**
     * Remove a lifecycle event listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removeLifecycleListener(LifecycleListener listener)
    {
        lifecycle.removeLifecycleListener(listener);
    }

    /**
     * Use as base to handle start/stop/periodic Events from host. Currently
     * only log the messages as trace level.
     *
     * @see org.apache.catalina.LifecycleListener#lifecycleEvent(org.apache.catalina.LifecycleEvent)
     */
    public void lifecycleEvent(LifecycleEvent lifecycleEvent)
    {
        if (log.isTraceEnabled())
            log.trace(sm.getString("SimpleTcpCluster.event.log", lifecycleEvent.getType(), lifecycleEvent.getData()));
    }

    // ------------------------------------------------------ public

    /**
     * Prepare for the beginning of active use of the public methods of this
     * component. This method should be called after <code>configure()</code>,
     * and before any of the public methods of the component are utilized. <BR>
     * Starts the cluster communication channel, this will connect with the
     * other nodes in the cluster, and request the current session state to be
     * transferred to this node.
     *
     * @throws IllegalStateException if this component has already been started
     * @throws LifecycleException    if this component detects a fatal error that prevents this
     *                               component from being used
     */
    public void start() throws LifecycleException
    {
        if (started)
            throw new LifecycleException(sm.getString("cluster.alreadyStarted"));
        if (log.isInfoEnabled()) log.info("Cluster is about to start");

        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_START_EVENT, this);
        try
        {
            checkDefaults();
            registerClusterValve();
            channel.addMembershipListener(this);
            channel.addChannelListener(this);
            channel.start(channelStartOptions);
            if (clusterDeployer != null) clusterDeployer.start();
            this.started = true;
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(AFTER_START_EVENT, this);
        }
        catch (Exception x)
        {
            log.error("Unable to start cluster.", x);
            throw new LifecycleException(x);
        }
    }

    protected void checkDefaults()
    {
        if (clusterListeners.size() == 0)
        {
            addClusterListener(new JvmRouteSessionIDBinderListener());
            addClusterListener(new ClusterSessionListener());
        }
        if (valves.size() == 0)
        {
            addValve(new JvmRouteBinderValve());
            addValve(new ReplicationValve());
        }
        if (clusterDeployer != null) clusterDeployer.setCluster(this);
        if (channel == null) channel = new GroupChannel();
        if (channel instanceof GroupChannel && !((GroupChannel) channel).getInterceptors().hasNext())
        {
            channel.addInterceptor(new MessageDispatch15Interceptor());
            channel.addInterceptor(new TcpFailureDetector());
        }
    }

    /**
     * register all cluster valve to host or engine
     *
     * @throws Exception
     * @throws ClassNotFoundException
     */
    protected void registerClusterValve() throws Exception
    {
        if (container != null)
        {
            for (Iterator iter = valves.iterator(); iter.hasNext(); )
            {
                ClusterValve valve = (ClusterValve) iter.next();
                if (log.isDebugEnabled())
                    log.debug("Invoking addValve on " + getContainer()
                            + " with class=" + valve.getClass().getName());
                if (valve != null)
                {
                    IntrospectionUtils.callMethodN(getContainer(), "addValve",
                            new Object[]{valve},
                            new Class[]{org.apache.catalina.Valve.class});

                }
                valve.setCluster(this);
            }
        }
    }

    /**
     * unregister all cluster valve to host or engine
     *
     * @throws Exception
     * @throws ClassNotFoundException
     */
    protected void unregisterClusterValve() throws Exception
    {
        for (Iterator iter = valves.iterator(); iter.hasNext(); )
        {
            ClusterValve valve = (ClusterValve) iter.next();
            if (log.isDebugEnabled())
                log.debug("Invoking removeValve on " + getContainer()
                        + " with class=" + valve.getClass().getName());
            if (valve != null)
            {
                IntrospectionUtils.callMethodN(getContainer(), "removeValve",
                        new Object[]{valve}, new Class[]{org.apache.catalina.Valve.class});
            }
            valve.setCluster(this);
        }
    }

    /**
     * Gracefully terminate the active cluster component.<br/>
     * This will disconnect the cluster communication channel, stop the
     * listener and deregister the valves from host or engine.<br/><br/>
     * <b>Note:</b><br/>The sub elements receiver, sender, membership,
     * listener or valves are not removed. You can easily start the cluster again.
     *
     * @throws IllegalStateException if this component has not been started
     * @throws LifecycleException    if this component detects a fatal error that needs to be
     *                               reported
     */
    public void stop() throws LifecycleException
    {

        if (!started)
            throw new IllegalStateException(sm.getString("cluster.notStarted"));
        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(BEFORE_STOP_EVENT, this);

        if (clusterDeployer != null) clusterDeployer.stop();
        this.managers.clear();
        try
        {
            if (clusterDeployer != null) clusterDeployer.setCluster(null);
            channel.stop(channelStartOptions);
            channel.removeChannelListener(this);
            channel.removeMembershipListener(this);
            this.unregisterClusterValve();
        }
        catch (Exception x)
        {
            log.error("Unable to stop cluster valve.", x);
        }
        started = false;
        // Notify our interested LifecycleListeners
        lifecycle.fireLifecycleEvent(AFTER_STOP_EVENT, this);
    }


    /**
     * send message to all cluster members
     *
     * @param msg message to transfer
     * @see org.apache.catalina.ha.CatalinaCluster#send(ClusterMessage)
     */
    public void send(ClusterMessage msg)
    {
        send(msg, null);
    }

    /**
     * send message to all cluster members same cluster domain
     *
     * @see org.apache.catalina.ha.CatalinaCluster#send(ClusterMessage)
     */
    public void sendClusterDomain(ClusterMessage msg)
    {
        send(msg, null);
    }


    /**
     * send a cluster message to one member
     *
     * @param msg  message to transfer
     * @param dest Receiver member
     * @see org.apache.catalina.ha.CatalinaCluster#send(ClusterMessage, Member)
     */
    public void send(ClusterMessage msg, Member dest)
    {
        try
        {
            msg.setAddress(getLocalMember());
            int sendOptions = channelSendOptions;
            if (msg instanceof SessionMessage
                    && ((SessionMessage) msg).getEventType() == SessionMessage.EVT_ALL_SESSION_DATA)
            {
                sendOptions = Channel.SEND_OPTIONS_SYNCHRONIZED_ACK | Channel.SEND_OPTIONS_USE_ACK;
            }
            if (dest != null)
            {
                if (!getLocalMember().equals(dest))
                {
                    channel.send(new Member[]{dest}, msg, sendOptions);
                } else
                    log.error("Unable to send message to local member " + msg);
            } else
            {
                if (channel.getMembers().length > 0)
                    channel.send(channel.getMembers(), msg, sendOptions);
                else if (log.isDebugEnabled())
                    log.debug("No members in cluster, ignoring message:" + msg);
            }
        }
        catch (Exception x)
        {
            log.error("Unable to send message through cluster sender.", x);
        }
    }

    /**
     * New cluster member is registered
     *
     * @see MembershipListener#memberAdded(Member)
     */
    public void memberAdded(Member member)
    {
        try
        {
            hasMembers = channel.hasMembers();
            if (log.isInfoEnabled()) log.info("Replication member added:" + member);
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(BEFORE_MEMBERREGISTER_EVENT, member);
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(AFTER_MEMBERREGISTER_EVENT, member);
        }
        catch (Exception x)
        {
            log.error("Unable to connect to replication system.", x);
        }

    }

    /**
     * Cluster member is gone
     *
     * @see MembershipListener#memberDisappeared(Member)
     */
    public void memberDisappeared(Member member)
    {
        try
        {
            hasMembers = channel.hasMembers();
            if (log.isInfoEnabled()) log.info("Received member disappeared:" + member);
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(BEFORE_MEMBERUNREGISTER_EVENT, member);
            // Notify our interested LifecycleListeners
            lifecycle.fireLifecycleEvent(AFTER_MEMBERUNREGISTER_EVENT, member);
        }
        catch (Exception x)
        {
            log.error("Unable remove cluster node from replication system.", x);
        }
    }

    // --------------------------------------------------------- receiver
    // messages

    /**
     * Notify all listeners from receiving a new message is not ClusterMessage
     * and emit Failure Event to LifecylceListener
     *
     * @param msg received Message
     */
    public boolean accept(Serializable msg, Member sender)
    {
        return (msg instanceof ClusterMessage);
    }


    public void messageReceived(Serializable message, Member sender)
    {
        ClusterMessage fwd = (ClusterMessage) message;
        fwd.setAddress(sender);
        messageReceived(fwd);
    }

    public void messageReceived(ClusterMessage message)
    {

        long start = 0;
        if (log.isDebugEnabled() && message != null)
            log.debug("Assuming clocks are synched: Replication for "
                    + message.getUniqueId() + " took="
                    + (System.currentTimeMillis() - (message).getTimestamp())
                    + " ms.");

        //invoke all the listeners
        boolean accepted = false;
        if (message != null)
        {
            for (Iterator iter = clusterListeners.iterator(); iter.hasNext(); )
            {
                ClusterListener listener = (ClusterListener) iter.next();
                if (listener.accept(message))
                {
                    accepted = true;
                    listener.messageReceived(message);
                }
            }
        }
        if (!accepted && log.isDebugEnabled())
        {
            if (notifyLifecycleListenerOnFailure)
            {
                Member dest = message.getAddress();
                // Notify our interested LifecycleListeners
                lifecycle.fireLifecycleEvent(RECEIVE_MESSAGE_FAILURE_EVENT,
                        new SendMessageData(message, dest, null));
            }
            log.debug("Message " + message.toString() + " from type "
                    + message.getClass().getName()
                    + " transfered but no listener registered");
        }
        return;
    }

    // --------------------------------------------------------- Logger

    public Log getLogger()
    {
        return log;
    }


    // ------------------------------------------------------------- deprecated

    /**
     * @see org.apache.catalina.Cluster#getProtocol()
     */
    public String getProtocol()
    {
        return null;
    }

    /**
     * @see org.apache.catalina.Cluster#setProtocol(java.lang.String)
     */
    public void setProtocol(String protocol)
    {
    }

    public int getChannelStartOptions()
    {
        return channelStartOptions;
    }

    public void setChannelStartOptions(int channelStartOptions)
    {
        this.channelStartOptions = channelStartOptions;
    }
}
