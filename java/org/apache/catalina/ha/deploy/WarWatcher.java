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

package org.apache.catalina.ha.deploy;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * <p>
 * The <b>WarWatcher </b> watches the deployDir for changes made to the
 * directory (adding new WAR files->deploy or remove WAR files->undeploy) And
 * notifies a listener of the changes made
 * </p>
 *
 * @author Filip Hanik
 * @author Peter Rossbach
 * @version 1.1
 */

public class WarWatcher
{

    /*--Static Variables----------------------------------------*/
    public static org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory
            .getLog(WarWatcher.class);

    /*--Instance Variables--------------------------------------*/
    /**
     * Directory to watch for war files
     */
    protected File watchDir = null;

    /**
     * Parent to be notified of changes
     */
    protected FileChangeListener listener = null;

    /**
     * Currently deployed files
     */
    protected Map currentStatus = new HashMap();

    /*--Constructor---------------------------------------------*/

    public WarWatcher()
    {
    }

    public WarWatcher(FileChangeListener listener, File watchDir)
    {
        this.listener = listener;
        this.watchDir = watchDir;
    }

    /*--Logic---------------------------------------------------*/

    /**
     * check for modification and send notifcation to listener
     */
    public void check()
    {
        if (log.isInfoEnabled())
            log.info("check cluster wars at " + watchDir);
        File[] list = watchDir.listFiles(new WarFilter());
        if (list == null)
            list = new File[0];
        //first make sure all the files are listed in our current status
        for (int i = 0; i < list.length; i++)
        {
            addWarInfo(list[i]);
        }

        //check all the status codes and update the FarmDeployer
        for (Iterator i = currentStatus.entrySet().iterator(); i.hasNext(); )
        {
            Map.Entry entry = (Map.Entry) i.next();
            WarInfo info = (WarInfo) entry.getValue();
            int check = info.check();
            if (check == 1)
            {
                listener.fileModified(info.getWar());
            } else if (check == -1)
            {
                listener.fileRemoved(info.getWar());
                //no need to keep in memory
                i.remove();
            }
        }

    }

    /**
     * add cluster war to the watcher state
     *
     * @param warfile
     */
    protected void addWarInfo(File warfile)
    {
        WarInfo info = (WarInfo) currentStatus.get(warfile.getAbsolutePath());
        if (info == null)
        {
            info = new WarInfo(warfile);
            info.setLastState(-1); //assume file is non existent
            currentStatus.put(warfile.getAbsolutePath(), info);
        }
    }

    /**
     * clear watcher state
     */
    public void clear()
    {
        currentStatus.clear();
    }

    /**
     * @return Returns the watchDir.
     */
    public File getWatchDir()
    {
        return watchDir;
    }

    /**
     * @param watchDir The watchDir to set.
     */
    public void setWatchDir(File watchDir)
    {
        this.watchDir = watchDir;
    }

    /**
     * @return Returns the listener.
     */
    public FileChangeListener getListener()
    {
        return listener;
    }

    /**
     * @param listener The listener to set.
     */
    public void setListener(FileChangeListener listener)
    {
        this.listener = listener;
    }

    /*--Inner classes-------------------------------------------*/

    /**
     * File name filter for war files
     */
    protected class WarFilter implements java.io.FilenameFilter
    {
        public boolean accept(File path, String name)
        {
            if (name == null)
                return false;
            return name.endsWith(".war");
        }
    }

    /**
     * File information on existing WAR files
     */
    protected class WarInfo
    {
        protected File war = null;

        protected long lastChecked = 0;

        protected long lastState = 0;

        public WarInfo(File war)
        {
            this.war = war;
            this.lastChecked = war.lastModified();
            if (!war.exists())
                lastState = -1;
        }

        public boolean modified()
        {
            return war.exists() && war.lastModified() > lastChecked;
        }

        public boolean exists()
        {
            return war.exists();
        }

        /**
         * Returns 1 if the file has been added/modified, 0 if the file is
         * unchanged and -1 if the file has been removed
         *
         * @return int 1=file added; 0=unchanged; -1=file removed
         */
        public int check()
        {
            //file unchanged by default
            int result = 0;

            if (modified())
            {
                //file has changed - timestamp
                result = 1;
                lastState = result;
            } else if ((!exists()) && (!(lastState == -1)))
            {
                //file was removed
                result = -1;
                lastState = result;
            } else if ((lastState == -1) && exists())
            {
                //file was added
                result = 1;
                lastState = result;
            }
            this.lastChecked = System.currentTimeMillis();
            return result;
        }

        public File getWar()
        {
            return war;
        }

        public int hashCode()
        {
            return war.getAbsolutePath().hashCode();
        }

        public boolean equals(Object other)
        {
            if (other instanceof WarInfo)
            {
                WarInfo wo = (WarInfo) other;
                return wo.getWar().equals(getWar());
            } else
            {
                return false;
            }
        }

        protected void setLastState(int lastState)
        {
            this.lastState = lastState;
        }

    }

}