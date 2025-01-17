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
package org.apache.catalina.valves;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This valve allows to detect requests that take a long time to process, which
 * might indicate that the thread that is processing it is stuck.
 */
public class StuckThreadDetectionValve extends ValveBase
{

    /**
     * Logger
     */
    private static final Log log = LogFactory.getLog(StuckThreadDetectionValve.class);

    /**
     * The string manager for this package.
     */
    private static final StringManager sm =
            StringManager.getManager(Constants.Package);

    /**
     * Keeps count of the number of stuck threads detected
     */
    private final AtomicInteger stuckCount = new AtomicInteger(0);
    /**
     * The only references we keep to actual running Thread objects are in
     * this Map (which is automatically cleaned in invoke()s finally clause).
     * That way, Threads can be GC'ed, eventhough the Valve still thinks they
     * are stuck (caused by a long monitor interval)
     */
    private final ConcurrentHashMap<Long, MonitoredThread> activeThreads =
            new ConcurrentHashMap<Long, MonitoredThread>();
    /**
     *
     */
    private final Queue<CompletedStuckThread> completedStuckThreadsQueue =
            new ConcurrentLinkedQueue<CompletedStuckThread>();
    /**
     * In seconds. Default 600 (10 minutes).
     */
    private int threshold = 600;

    /**
     * @return The current threshold in seconds
     * @see #setThreshold(int)
     */
    public int getThreshold()
    {
        return threshold;
    }

    /**
     * Specify the threshold (in seconds) used when checking for stuck threads.
     * If &lt;=0, the detection is disabled. The default is 600 seconds.
     *
     * @param threshold The new threshold in seconds
     */
    public void setThreshold(int threshold)
    {
        this.threshold = threshold;
    }

    private void notifyStuckThreadDetected(MonitoredThread monitoredThread,
                                           long activeTime, int numStuckThreads)
    {
        if (log.isWarnEnabled())
        {
            String msg = sm.getString(
                    "stuckThreadDetectionValve.notifyStuckThreadDetected",
                    monitoredThread.getThread().getName(),
                    Long.valueOf(activeTime),
                    monitoredThread.getStartTime(),
                    Integer.valueOf(numStuckThreads),
                    monitoredThread.getRequestUri(),
                    Integer.valueOf(threshold),
                    String.valueOf(monitoredThread.getThread().getId())
            );
            // msg += "\n" + getStackTraceAsString(trace);
            Throwable th = new Throwable();
            th.setStackTrace(monitoredThread.getThread().getStackTrace());
            log.warn(msg, th);
        }
    }

    private void notifyStuckThreadCompleted(CompletedStuckThread thread,
                                            int numStuckThreads)
    {
        if (log.isWarnEnabled())
        {
            String msg = sm.getString(
                    "stuckThreadDetectionValve.notifyStuckThreadCompleted",
                    thread.getName(),
                    Long.valueOf(thread.getTotalActiveTime()),
                    Integer.valueOf(numStuckThreads),
                    String.valueOf(thread.getId()));
            // Since the "stuck thread notification" is warn, this should also
            // be warn
            log.warn(msg);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invoke(Request request, Response response)
            throws IOException, ServletException
    {

        if (threshold <= 0)
        {
            // short-circuit if not monitoring stuck threads
            getNext().invoke(request, response);
            return;
        }

        // Save the thread/runnable
        // Keeping a reference to the thread object here does not prevent
        // GC'ing, as the reference is removed from the Map in the finally clause

        Long key = Long.valueOf(Thread.currentThread().getId());
        StringBuffer requestUrl = request.getRequestURL();
        if (request.getQueryString() != null)
        {
            requestUrl.append("?");
            requestUrl.append(request.getQueryString());
        }
        MonitoredThread monitoredThread = new MonitoredThread(Thread.currentThread(),
                requestUrl.toString());
        activeThreads.put(key, monitoredThread);

        try
        {
            getNext().invoke(request, response);
        }
        finally
        {
            activeThreads.remove(key);
            if (monitoredThread.markAsDone() == MonitoredThreadState.STUCK)
            {
                completedStuckThreadsQueue.add(
                        new CompletedStuckThread(monitoredThread.getThread(),
                                monitoredThread.getActiveTimeInMillis()));
            }
        }
    }

    @Override
    public void backgroundProcess()
    {
        super.backgroundProcess();

        long thresholdInMillis = threshold * 1000;

        // Check monitored threads, being careful that the request might have
        // completed by the time we examine it
        for (MonitoredThread monitoredThread : activeThreads.values())
        {
            long activeTime = monitoredThread.getActiveTimeInMillis();

            if (activeTime >= thresholdInMillis && monitoredThread.markAsStuckIfStillRunning())
            {
                int numStuckThreads = stuckCount.incrementAndGet();
                notifyStuckThreadDetected(monitoredThread, activeTime, numStuckThreads);
            }
        }
        // Check if any threads previously reported as stuck, have finished.
        for (CompletedStuckThread completedStuckThread = completedStuckThreadsQueue.poll();
             completedStuckThread != null; completedStuckThread = completedStuckThreadsQueue.poll())
        {

            int numStuckThreads = stuckCount.decrementAndGet();
            notifyStuckThreadCompleted(completedStuckThread, numStuckThreads);
        }
    }

    public long[] getStuckThreadIds()
    {
        List<Long> idList = new ArrayList<Long>();
        for (MonitoredThread monitoredThread : activeThreads.values())
        {
            if (monitoredThread.isMarkedAsStuck())
            {
                idList.add(Long.valueOf(monitoredThread.getThread().getId()));
            }
        }

        long[] result = new long[idList.size()];
        for (int i = 0; i < result.length; i++)
        {
            result[i] = idList.get(i).longValue();
        }
        return result;
    }

    public String[] getStuckThreadNames()
    {
        List<String> nameList = new ArrayList<String>();
        for (MonitoredThread monitoredThread : activeThreads.values())
        {
            if (monitoredThread.isMarkedAsStuck())
            {
                nameList.add(monitoredThread.getThread().getName());
            }
        }
        return nameList.toArray(new String[nameList.size()]);
    }

    private enum MonitoredThreadState
    {
        RUNNING, STUCK, DONE;
    }

    private static class MonitoredThread
    {

        /**
         * Reference to the thread to get a stack trace from background task
         */
        private final Thread thread;
        private final String requestUri;
        private final long start;
        private final AtomicInteger state = new AtomicInteger(
                MonitoredThreadState.RUNNING.ordinal());

        public MonitoredThread(Thread thread, String requestUri)
        {
            this.thread = thread;
            this.requestUri = requestUri;
            this.start = System.currentTimeMillis();
        }

        public Thread getThread()
        {
            return this.thread;
        }

        public String getRequestUri()
        {
            return requestUri;
        }

        public long getActiveTimeInMillis()
        {
            return System.currentTimeMillis() - start;
        }

        public Date getStartTime()
        {
            return new Date(start);
        }

        public boolean markAsStuckIfStillRunning()
        {
            return this.state.compareAndSet(MonitoredThreadState.RUNNING.ordinal(),
                    MonitoredThreadState.STUCK.ordinal());
        }

        public MonitoredThreadState markAsDone()
        {
            int val = this.state.getAndSet(MonitoredThreadState.DONE.ordinal());
            return MonitoredThreadState.values()[val];
        }

        boolean isMarkedAsStuck()
        {
            return this.state.get() == MonitoredThreadState.STUCK.ordinal();
        }
    }

    private static class CompletedStuckThread
    {

        private final String threadName;
        private final long threadId;
        private final long totalActiveTime;

        public CompletedStuckThread(Thread thread, long totalActiveTime)
        {
            this.threadName = thread.getName();
            this.threadId = thread.getId();
            this.totalActiveTime = totalActiveTime;
        }

        public String getName()
        {
            return this.threadName;
        }

        public long getId()
        {
            return this.threadId;
        }

        public long getTotalActiveTime()
        {
            return this.totalActiveTime;
        }
    }
}
