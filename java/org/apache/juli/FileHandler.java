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


package org.apache.juli;

import java.io.*;
import java.sql.Timestamp;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.*;

/**
 * Implementation of <b>Handler</b> that appends log messages to a file
 * named {prefix}{date}{suffix} in a configured directory.
 * <p/>
 * <p>The following configuration properties are available:</p>
 * <p/>
 * <ul>
 * <li><code>directory</code> - The directory where to create the log file.
 * If the path is not absolute, it is relative to the current working
 * directory of the application. The Apache Tomcat configuration files usually
 * specify an absolute path for this property,
 * <code>${catalina.base}/logs</code>
 * Default value: <code>logs</code></li>
 * <li><code>rotatable</code> - If <code>true</code>, the log file will be
 * rotated on the first write past midnight and the filename will be
 * <code>{prefix}{date}{suffix}</code>, where date is yyyy-MM-dd. If <code>false</code>,
 * the file will not be rotated and the filename will be <code>{prefix}{suffix}</code>.
 * Default value: <code>true</code></li>
 * <li><code>prefix</code> - The leading part of the log file name.
 * Default value: <code>juli.</code></li>
 * <li><code>suffix</code> - The trailing part of the log file name. Default value: <code>.log</code></li>
 * <li><code>bufferSize</code> - Configures buffering. The value of <code>0</code>
 * uses system default buffering (typically an 8K buffer will be used). A
 * value of <code>&lt;0</code> forces a writer flush upon each log write. A
 * value <code>&gt;0</code> uses a BufferedOutputStream with the defined
 * value but note that the system default buffering will also be
 * applied. Default value: <code>-1</code></li>
 * <li><code>encoding</code> - Character set used by the log file. Default value:
 * empty string, which means to use the system default character set.</li>
 * <li><code>level</code> - The level threshold for this Handler. See the
 * <code>java.util.logging.Level</code> class for the possible levels.
 * Default value: <code>ALL</code></li>
 * <li><code>filter</code> - The <code>java.util.logging.Filter</code>
 * implementation class name for this Handler. Default value: unset</li>
 * <li><code>formatter</code> - The <code>java.util.logging.Formatter</code>
 * implementation class name for this Handler. Default value:
 * <code>java.util.logging.SimpleFormatter</code></li>
 * </ul>
 */

public class FileHandler
        extends Handler
{


    // ------------------------------------------------------------ Constructor


    /**
     * Lock used to control access to the writer.
     */
    protected ReadWriteLock writerLock = new ReentrantReadWriteLock();
    /**
     * The as-of date for the currently open log file, or a zero-length
     * string if there is no open log file.
     */
    private volatile String date = "";


    // ----------------------------------------------------- Instance Variables
    /**
     * The directory in which log files are created.
     */
    private String directory = null;
    /**
     * The prefix that is added to log file filenames.
     */
    private String prefix = null;
    /**
     * The suffix that is added to log file filenames.
     */
    private String suffix = null;
    /**
     * Determines whether the logfile is rotatable
     */
    private boolean rotatable = true;
    /**
     * The PrintWriter to which we are currently logging, if any.
     */
    private volatile PrintWriter writer = null;
    /**
     * Log buffer size.
     */
    private int bufferSize = -1;


    public FileHandler()
    {
        this(null, null, null);
    }


    public FileHandler(String directory, String prefix, String suffix)
    {
        this.directory = directory;
        this.prefix = prefix;
        this.suffix = suffix;
        configure();
        openWriter();
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Format and publish a <tt>LogRecord</tt>.
     *
     * @param record description of the log event
     */
    public void publish(LogRecord record)
    {

        if (!isLoggable(record))
        {
            return;
        }

        // Construct the timestamp we will use, if requested
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        String tsString = ts.toString().substring(0, 19);
        String tsDate = tsString.substring(0, 10);

        writerLock.readLock().lock();
        // If the date has changed, switch log files
        if (rotatable && !date.equals(tsDate))
        {
            // Update to writeLock before we switch
            writerLock.readLock().unlock();
            writerLock.writeLock().lock();
            try
            {
                // Make sure another thread hasn't already done this
                if (!date.equals(tsDate))
                {
                    closeWriter();
                    date = tsDate;
                    openWriter();
                }
                // Down grade to read-lock. This ensures the writer remains valid
                // until the log message is written
                writerLock.readLock().lock();
            }
            finally
            {
                writerLock.writeLock().unlock();
            }
        }

        try
        {
            String result = null;
            try
            {
                result = getFormatter().format(record);
            }
            catch (Exception e)
            {
                reportError(null, e, ErrorManager.FORMAT_FAILURE);
                return;
            }

            try
            {
                if (writer != null)
                {
                    writer.write(result);
                    if (bufferSize < 0)
                    {
                        writer.flush();
                    }
                } else
                {
                    reportError("FileHandler is closed or not yet initialized, unable to log [" + result + "]", null, ErrorManager.WRITE_FAILURE);
                }
            }
            catch (Exception e)
            {
                reportError(null, e, ErrorManager.WRITE_FAILURE);
                return;
            }
        }
        finally
        {
            writerLock.readLock().unlock();
        }
    }


    // -------------------------------------------------------- Private Methods


    /**
     * Close the currently open log file (if any).
     */
    public void close()
    {
        closeWriter();
    }

    protected void closeWriter()
    {

        writerLock.writeLock().lock();
        try
        {
            if (writer == null)
                return;
            writer.write(getFormatter().getTail(this));
            writer.flush();
            writer.close();
            writer = null;
            date = "";
        }
        catch (Exception e)
        {
            reportError(null, e, ErrorManager.CLOSE_FAILURE);
        }
        finally
        {
            writerLock.writeLock().unlock();
        }
    }


    /**
     * Flush the writer.
     */
    public void flush()
    {

        writerLock.readLock().lock();
        try
        {
            if (writer == null)
                return;
            writer.flush();
        }
        catch (Exception e)
        {
            reportError(null, e, ErrorManager.FLUSH_FAILURE);
        }
        finally
        {
            writerLock.readLock().unlock();
        }

    }


    /**
     * Configure from <code>LogManager</code> properties.
     */
    private void configure()
    {

        Timestamp ts = new Timestamp(System.currentTimeMillis());
        String tsString = ts.toString().substring(0, 19);
        date = tsString.substring(0, 10);

        String className = this.getClass().getName(); //allow classes to override

        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Retrieve configuration of logging file name
        rotatable = Boolean.parseBoolean(getProperty(className + ".rotatable", "true"));
        if (directory == null)
            directory = getProperty(className + ".directory", "logs");
        if (prefix == null)
            prefix = getProperty(className + ".prefix", "juli.");
        if (suffix == null)
            suffix = getProperty(className + ".suffix", ".log");
        String sBufferSize = getProperty(className + ".bufferSize", String.valueOf(bufferSize));
        try
        {
            bufferSize = Integer.parseInt(sBufferSize);
        }
        catch (NumberFormatException ignore)
        {
            //no op
        }
        // Get encoding for the logging file
        String encoding = getProperty(className + ".encoding", null);
        if (encoding != null && encoding.length() > 0)
        {
            try
            {
                setEncoding(encoding);
            }
            catch (UnsupportedEncodingException ex)
            {
                // Ignore
            }
        }

        // Get logging level for the handler
        setLevel(Level.parse(getProperty(className + ".level", "" + Level.ALL)));

        // Get filter configuration
        String filterName = getProperty(className + ".filter", null);
        if (filterName != null)
        {
            try
            {
                setFilter((Filter) cl.loadClass(filterName).newInstance());
            }
            catch (Exception e)
            {
                // Ignore
            }
        }

        // Set formatter
        String formatterName = getProperty(className + ".formatter", null);
        if (formatterName != null)
        {
            try
            {
                setFormatter((Formatter) cl.loadClass(formatterName).newInstance());
            }
            catch (Exception e)
            {
                // Ignore and fallback to defaults
                setFormatter(new SimpleFormatter());
            }
        } else
        {
            setFormatter(new SimpleFormatter());
        }

        // Set error manager
        setErrorManager(new ErrorManager());

    }


    private String getProperty(String name, String defaultValue)
    {
        String value = LogManager.getLogManager().getProperty(name);
        if (value == null)
        {
            value = defaultValue;
        } else
        {
            value = value.trim();
        }
        return value;
    }


    /**
     * Open the new log file for the date specified by <code>date</code>.
     */
    protected void open()
    {
        openWriter();
    }

    protected void openWriter()
    {

        // Create the directory if necessary
        File dir = new File(directory);
        dir.mkdirs();

        // Open the current log file
        writerLock.writeLock().lock();
        try
        {
            File pathname = new File(dir.getAbsoluteFile(), prefix
                    + (rotatable ? date : "") + suffix);
            File parent = pathname.getParentFile();
            if (!parent.exists())
            {
                parent.mkdirs();
            }
            String encoding = getEncoding();
            FileOutputStream fos = new FileOutputStream(pathname, true);
            OutputStream os = bufferSize > 0 ? new BufferedOutputStream(fos, bufferSize) : fos;
            writer = new PrintWriter(
                    (encoding != null) ? new OutputStreamWriter(os, encoding)
                            : new OutputStreamWriter(os), false);
            writer.write(getFormatter().getHead(this));
        }
        catch (Exception e)
        {
            reportError(null, e, ErrorManager.OPEN_FAILURE);
            writer = null;
        }
        finally
        {
            writerLock.writeLock().unlock();
        }

    }


}
