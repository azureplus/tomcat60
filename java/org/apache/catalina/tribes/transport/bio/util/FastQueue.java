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

package org.apache.catalina.tribes.transport.bio.util;

import org.apache.catalina.tribes.ChannelMessage;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.group.InterceptorPayload;


/**
 * A fast queue that remover thread lock the adder thread. <br/>Limit the queue
 * length when you have strange producer thread problemes.
 * <p/>
 * FIXME add i18n support to log messages
 *
 * @author Rainer Jung
 * @author Peter Rossbach
 */
public class FastQueue
{

    private static org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory.getLog(FastQueue.class);

    /**
     * This is the actual queue
     */
    private SingleRemoveSynchronizedAddLock lock = null;

    /**
     * First Object at queue (consumer message)
     */
    private LinkObject first = null;

    /**
     * Last object in queue (producer Object)
     */
    private LinkObject last = null;

    /**
     * Current Queue elements size
     */
    private int size = 0;

    /**
     * check lock to detect strange threadings things
     */
    private boolean checkLock = false;

    /**
     * protocol the thread wait times
     */
    private boolean timeWait = false;

    private boolean inAdd = false;

    private boolean inRemove = false;

    private boolean inMutex = false;

    /**
     * limit the queue legnth ( default is unlimited)
     */
    private int maxQueueLength = 0;

    /**
     * addWaitTimeout for producer
     */
    private long addWaitTimeout = 10000L;


    /**
     * removeWaitTimeout for consumer
     */
    private long removeWaitTimeout = 30000L;

    /**
     * enabled the queue
     */
    private boolean enabled = true;

    /**
     * max queue size
     */
    private int maxSize = 0;

    /**
     * avg size sample interval
     */
    private int sampleInterval = 100;

    /**
     * Generate Queue SingleRemoveSynchronizedAddLock and set add and wait
     * Timeouts
     */
    public FastQueue()
    {
        lock = new SingleRemoveSynchronizedAddLock();
        lock.setAddWaitTimeout(addWaitTimeout);
        lock.setRemoveWaitTimeout(removeWaitTimeout);
    }

    /**
     * get current add wait timeout
     *
     * @return current wait timeout
     */
    public long getAddWaitTimeout()
    {
        addWaitTimeout = lock.getAddWaitTimeout();
        return addWaitTimeout;
    }

    /**
     * Set add wait timeout (default 10000 msec)
     *
     * @param timeout
     */
    public void setAddWaitTimeout(long timeout)
    {
        addWaitTimeout = timeout;
        lock.setAddWaitTimeout(addWaitTimeout);
    }

    /**
     * get current remove wait timeout
     *
     * @return The timeout
     */
    public long getRemoveWaitTimeout()
    {
        removeWaitTimeout = lock.getRemoveWaitTimeout();
        return removeWaitTimeout;
    }

    /**
     * set remove wait timeout ( default 30000 msec)
     *
     * @param timeout
     */
    public void setRemoveWaitTimeout(long timeout)
    {
        removeWaitTimeout = timeout;
        lock.setRemoveWaitTimeout(removeWaitTimeout);
    }


    public int getMaxQueueLength()
    {
        return maxQueueLength;
    }

    public void setMaxQueueLength(int length)
    {
        maxQueueLength = length;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enable)
    {
        enabled = enable;
        if (!enabled)
        {
            lock.abortRemove();
            last = first = null;
        }
    }

    /**
     * @return Returns the checkLock.
     */
    public boolean isCheckLock()
    {
        return checkLock;
    }

    /**
     * @param checkLock The checkLock to set.
     */
    public void setCheckLock(boolean checkLock)
    {
        this.checkLock = checkLock;
    }


    /**
     * @return The max size
     */
    public int getMaxSize()
    {
        return maxSize;
    }

    /**
     * @param size
     */
    public void setMaxSize(int size)
    {
        maxSize = size;
    }


    /**
     * unlock queue for next add
     */
    public void unlockAdd()
    {
        lock.unlockAdd(size > 0 ? true : false);
    }

    /**
     * unlock queue for next remove
     */
    public void unlockRemove()
    {
        lock.unlockRemove();
    }

    /**
     * start queuing
     */
    public void start()
    {
        setEnabled(true);
    }

    /**
     * start queuing
     */
    public void stop()
    {
        setEnabled(false);
    }

    public int getSize()
    {
        return size;
    }

    public SingleRemoveSynchronizedAddLock getLock()
    {
        return lock;
    }

    /**
     * Add new data to the queue
     * FIXME extract some method
     */
    public boolean add(ChannelMessage msg, Member[] destination, InterceptorPayload payload)
    {
        boolean ok = true;
        long time = 0;

        if (!enabled)
        {
            if (log.isInfoEnabled())
                log.info("FastQueue.add: queue disabled, add aborted");
            return false;
        }

        if (timeWait)
        {
            time = System.currentTimeMillis();
        }
        lock.lockAdd();
        try
        {
            if (log.isTraceEnabled())
            {
                log.trace("FastQueue.add: starting with size " + size);
            }
            if (checkLock)
            {
                if (inAdd)
                    log.warn("FastQueue.add: Detected other add");
                inAdd = true;
                if (inMutex)
                    log.warn("FastQueue.add: Detected other mutex in add");
                inMutex = true;
            }

            if ((maxQueueLength > 0) && (size >= maxQueueLength))
            {
                ok = false;
                if (log.isTraceEnabled())
                {
                    log.trace("FastQueue.add: Could not add, since queue is full (" + size + ">=" + maxQueueLength + ")");
                }
            } else
            {
                LinkObject element = new LinkObject(msg, destination, payload);
                if (size == 0)
                {
                    first = last = element;
                    size = 1;
                } else
                {
                    if (last == null)
                    {
                        ok = false;
                        log.error("FastQueue.add: Could not add, since last is null although size is " + size + " (>0)");
                    } else
                    {
                        last.append(element);
                        last = element;
                        size++;
                    }
                }
            }

            if (first == null)
            {
                log.error("FastQueue.add: first is null, size is " + size + " at end of add");
            }
            if (last == null)
            {
                log.error("FastQueue.add: last is null, size is " + size + " at end of add");
            }

            if (checkLock)
            {
                if (!inMutex) log.warn("FastQueue.add: Cancelled by other mutex in add");
                inMutex = false;
                if (!inAdd) log.warn("FastQueue.add: Cancelled by other add");
                inAdd = false;
            }
            if (log.isTraceEnabled()) log.trace("FastQueue.add: add ending with size " + size);

        }
        finally
        {
            lock.unlockAdd(true);
        }
        return ok;
    }

    /**
     * remove the complete queued object list
     * FIXME extract some method
     */
    public LinkObject remove()
    {
        LinkObject element;
        boolean gotLock;
        long time = 0;

        if (!enabled)
        {
            if (log.isInfoEnabled())
                log.info("FastQueue.remove: queue disabled, remove aborted");
            return null;
        }

        if (timeWait)
        {
            time = System.currentTimeMillis();
        }
        gotLock = lock.lockRemove();
        try
        {

            if (!gotLock)
            {
                if (enabled)
                {
                    if (log.isInfoEnabled())
                        log.info("FastQueue.remove: Remove aborted although queue enabled");
                } else
                {
                    if (log.isInfoEnabled())
                        log.info("FastQueue.remove: queue disabled, remove aborted");
                }
                return null;
            }

            if (log.isTraceEnabled())
            {
                log.trace("FastQueue.remove: remove starting with size " + size);
            }
            if (checkLock)
            {
                if (inRemove)
                    log.warn("FastQueue.remove: Detected other remove");
                inRemove = true;
                if (inMutex)
                    log.warn("FastQueue.remove: Detected other mutex in remove");
                inMutex = true;
            }

            element = first;

            first = last = null;
            size = 0;

            if (checkLock)
            {
                if (!inMutex)
                    log.warn("FastQueue.remove: Cancelled by other mutex in remove");
                inMutex = false;
                if (!inRemove)
                    log.warn("FastQueue.remove: Cancelled by other remove");
                inRemove = false;
            }
            if (log.isTraceEnabled())
            {
                log.trace("FastQueue.remove: remove ending with size " + size);
            }

            if (timeWait)
            {
                time = System.currentTimeMillis();
            }
        }
        finally
        {
            lock.unlockRemove();
        }
        return element;
    }

}
