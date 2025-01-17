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

package org.apache.catalina.core;


import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.jni.Library;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.util.ExceptionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;


/**
 * Implementation of <code>LifecycleListener</code> that will init and
 * and destroy APR.
 *
 * @author Remy Maucherat
 * @author Filip Hanik
 * @since 4.1
 */

public class AprLifecycleListener
        implements LifecycleListener
{

    protected static final int TCN_REQUIRED_MAJOR = 1;
    protected static final int TCN_REQUIRED_MINOR = 1;
    protected static final int TCN_REQUIRED_PATCH = 30;


    // ---------------------------------------------- Constants
    protected static final int TCN_RECOMMENDED_PV = 33;
    protected static final Object lock = new Object();
    /**
     * The "FIPS mode" level that we use as the argument to OpenSSL method
     * <code>FIPS_mode_set()</code> to enable FIPS mode and that we expect as
     * the return value of <code>FIPS_mode()</code> when FIPS mode is enabled.
     * <p>
     * In the future the OpenSSL library might grow support for different
     * non-zero "FIPS" modes that specify different allowed subsets of ciphers
     * or whatever, but nowadays only "1" is the supported value.
     * </p>
     *
     * @see <a href="http://wiki.openssl.org/index.php/FIPS_mode_set%28%29">OpenSSL method FIPS_mode_set()</a>
     * @see <a href="http://wiki.openssl.org/index.php/FIPS_mode%28%29">OpenSSL method FIPS_mode()</a>
     */
    private static final int FIPS_ON = 1;
    private static final int FIPS_OFF = 0;
    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);
    // ---------------------------------------------- Properties
    protected static String SSLEngine = "on"; //default on
    protected static String FIPSMode = "off"; // default off, valid only when SSLEngine="on"
    protected static String SSLRandomSeed = "builtin";
    protected static boolean sslInitialized = false;
    protected static boolean aprInitialized = false;
    protected static boolean sslAvailable = false;
    protected static boolean aprAvailable = false;
    protected static boolean fipsModeActive = false;
    private static Log log = LogFactory.getLog(AprLifecycleListener.class);
    private static boolean instanceCreated = false;

    public AprLifecycleListener()
    {
        instanceCreated = true;
    }

    public static boolean isAprAvailable()
    {
        //https://bz.apache.org/bugzilla/show_bug.cgi?id=48613
        if (instanceCreated)
        {
            synchronized (lock)
            {
                init();
            }
        }
        return aprAvailable;
    }

    // ---------------------------------------------- LifecycleListener Methods

    private static void terminateAPR()
            throws ClassNotFoundException, NoSuchMethodException,
            IllegalAccessException, InvocationTargetException
    {
        String methodName = "terminate";
        Method method = Class.forName("org.apache.tomcat.jni.Library")
                .getMethod(methodName, (Class[]) null);
        method.invoke(null, (Object[]) null);
        aprAvailable = false;
        aprInitialized = false;
        sslInitialized = false; // Well we cleaned the pool in terminate.
        sslAvailable = false; // Well we cleaned the pool in terminate.
        fipsModeActive = false;
    }

    private static void init()
    {
        int major = 0;
        int minor = 0;
        int patch = 0;
        int apver = 0;
        int rqver = TCN_REQUIRED_MAJOR * 1000 + TCN_REQUIRED_MINOR * 100 + TCN_REQUIRED_PATCH;
        int rcver = TCN_REQUIRED_MAJOR * 1000 + TCN_REQUIRED_MINOR * 100 + TCN_RECOMMENDED_PV;
        if (aprInitialized)
        {
            return;
        }
        aprInitialized = true;

        try
        {
            String methodName = "initialize";
            Class<?> paramTypes[] = new Class[1];
            paramTypes[0] = String.class;
            Object paramValues[] = new Object[1];
            paramValues[0] = null;
            Class<?> clazz = Class.forName("org.apache.tomcat.jni.Library");
            Method method = clazz.getMethod(methodName, paramTypes);
            method.invoke(null, paramValues);
            major = clazz.getField("TCN_MAJOR_VERSION").getInt(null);
            minor = clazz.getField("TCN_MINOR_VERSION").getInt(null);
            patch = clazz.getField("TCN_PATCH_VERSION").getInt(null);
            apver = major * 1000 + minor * 100 + patch;
        }
        catch (Throwable t)
        {
            ExceptionUtils.handleThrowable(t);
            if (!log.isDebugEnabled())
            {
                log.info(sm.getString("aprListener.aprInit",
                        System.getProperty("java.library.path")));
            } else
            {
                log.debug(sm.getString("aprListener.aprInit",
                        System.getProperty("java.library.path")), t);
            }
            return;
        }
        if (apver < rqver)
        {
            log.error(sm.getString("aprListener.tcnInvalid", major + "."
                            + minor + "." + patch,
                    TCN_REQUIRED_MAJOR + "." +
                            TCN_REQUIRED_MINOR + "." +
                            TCN_REQUIRED_PATCH));
            try
            {
                // Terminate the APR in case the version
                // is below required.
                terminateAPR();
            }
            catch (Throwable t)
            {
                ExceptionUtils.handleThrowable(t);
            }
            return;
        }
        if (apver < rcver)
        {
            if (!log.isDebugEnabled())
            {
                log.info(sm.getString("aprListener.tcnVersion", major + "."
                                + minor + "." + patch,
                        TCN_REQUIRED_MAJOR + "." +
                                TCN_REQUIRED_MINOR + "." +
                                TCN_RECOMMENDED_PV));
            } else
            {
                log.debug(sm.getString("aprListener.tcnVersion", major + "."
                                + minor + "." + patch,
                        TCN_REQUIRED_MAJOR + "." +
                                TCN_REQUIRED_MINOR + "." +
                                TCN_RECOMMENDED_PV));
            }
        }
        if (!log.isDebugEnabled())
        {
            log.info(sm.getString("aprListener.tcnValid", major + "."
                            + minor + "." + patch,
                    Library.APR_MAJOR_VERSION + "."
                            + Library.APR_MINOR_VERSION + "."
                            + Library.APR_PATCH_VERSION));
        } else
        {
            log.debug(sm.getString("aprListener.tcnValid", major + "."
                    + minor + "." + patch));
        }
        // Log APR flags
        log.info(sm.getString("aprListener.flags",
                Boolean.valueOf(Library.APR_HAVE_IPV6),
                Boolean.valueOf(Library.APR_HAS_SENDFILE),
                Boolean.valueOf(Library.APR_HAS_SO_ACCEPTFILTER),
                Boolean.valueOf(Library.APR_HAS_RANDOM)));
        aprAvailable = true;
    }

    private static void initializeSSL() throws Exception
    {

        if ("off".equalsIgnoreCase(SSLEngine))
        {
            return;
        }
        if (sslInitialized)
        {
            //only once per VM
            return;
        }

        sslInitialized = true;

        String methodName = "randSet";
        Class<?> paramTypes[] = new Class[1];
        paramTypes[0] = String.class;
        Object paramValues[] = new Object[1];
        paramValues[0] = SSLRandomSeed;
        Class<?> clazz = Class.forName("org.apache.tomcat.jni.SSL");
        Method method = clazz.getMethod(methodName, paramTypes);
        method.invoke(null, paramValues);


        methodName = "initialize";
        paramValues[0] = "on".equalsIgnoreCase(SSLEngine) ? null : SSLEngine;
        method = clazz.getMethod(methodName, paramTypes);
        method.invoke(null, paramValues);

        if (!(null == FIPSMode || "off".equalsIgnoreCase(FIPSMode)))
        {

            fipsModeActive = false;

            final boolean enterFipsMode;
            int fipsModeState = SSL.fipsModeGet();

            if (log.isDebugEnabled())
            {
                log.debug(sm.getString("aprListener.currentFIPSMode",
                        Integer.valueOf(fipsModeState)));
            }

            if ("on".equalsIgnoreCase(FIPSMode))
            {
                if (fipsModeState == FIPS_ON)
                {
                    log.info(sm.getString("aprListener.skipFIPSInitialization"));
                    fipsModeActive = true;
                    enterFipsMode = false;
                } else
                {
                    enterFipsMode = true;
                }
            } else if ("require".equalsIgnoreCase(FIPSMode))
            {
                if (fipsModeState == FIPS_ON)
                {
                    fipsModeActive = true;
                    enterFipsMode = false;
                } else
                {
                    throw new IllegalStateException(
                            sm.getString("aprListener.requireNotInFIPSMode"));
                }
            } else if ("enter".equalsIgnoreCase(FIPSMode))
            {
                if (fipsModeState == FIPS_OFF)
                {
                    enterFipsMode = true;
                } else
                {
                    throw new IllegalStateException(sm.getString(
                            "aprListener.enterAlreadyInFIPSMode",
                            Integer.valueOf(fipsModeState)));
                }
            } else
            {
                throw new IllegalArgumentException(sm.getString(
                        "aprListener.wrongFIPSMode", FIPSMode));
            }

            if (enterFipsMode)
            {
                log.info(sm.getString("aprListener.initializingFIPS"));

                fipsModeState = SSL.fipsModeSet(FIPS_ON);
                if (fipsModeState != FIPS_ON)
                {
                    // This case should be handled by the native method,
                    // but we'll make absolutely sure, here.
                    String message = sm.getString("aprListener.initializeFIPSFailed");
                    log.error(message);
                    throw new IllegalStateException(message);
                }

                fipsModeActive = true;
                log.info(sm.getString("aprListener.initializeFIPSSuccess"));
            }
        }

        log.info(sm.getString("aprListener.initializedOpenSSL", SSL.versionString()));

        sslAvailable = true;
    }

    /**
     * Primary entry point for startup and shutdown events.
     *
     * @param event The event that has occurred
     */
    public void lifecycleEvent(LifecycleEvent event)
    {

        if (Lifecycle.INIT_EVENT.equals(event.getType()))
        {
            synchronized (lock)
            {
                init();
                if (aprAvailable)
                {
                    try
                    {
                        initializeSSL();
                    }
                    catch (Throwable t)
                    {
                        ExceptionUtils.handleThrowable(t);
                        log.error(sm.getString("aprListener.sslInit"), t);
                    }
                }
                // Failure to initialize FIPS mode is fatal
                if (!(null == FIPSMode || "off".equalsIgnoreCase(FIPSMode)) && !isFIPSModeActive())
                {
                    Error e = new Error(
                            sm.getString("aprListener.initializeFIPSFailed"));
                    // Log here, because thrown error might be not logged
                    log.fatal(e.getMessage(), e);
                    throw e;
                }
            }
        } else if (Lifecycle.AFTER_STOP_EVENT.equals(event.getType()))
        {
            synchronized (lock)
            {
                if (!aprAvailable)
                {
                    return;
                }
                try
                {
                    terminateAPR();
                }
                catch (Throwable t)
                {
                    ExceptionUtils.handleThrowable(t);
                    if (!log.isDebugEnabled())
                    {
                        log.info(sm.getString("aprListener.aprDestroy"));
                    } else
                    {
                        log.debug(sm.getString("aprListener.aprDestroy"), t);
                    }
                }
            }
        }

    }

    public String getSSLEngine()
    {
        return SSLEngine;
    }

    public void setSSLEngine(String SSLEngine)
    {
        if (!SSLEngine.equals(AprLifecycleListener.SSLEngine))
        {
            // Ensure that the SSLEngine is consistent with that used for SSL init
            if (sslInitialized)
            {
                throw new IllegalStateException(
                        sm.getString("aprListener.tooLateForSSLEngine"));
            }

            AprLifecycleListener.SSLEngine = SSLEngine;
        }
    }

    public String getSSLRandomSeed()
    {
        return SSLRandomSeed;
    }

    public void setSSLRandomSeed(String SSLRandomSeed)
    {
        if (!SSLRandomSeed.equals(AprLifecycleListener.SSLRandomSeed))
        {
            // Ensure that the random seed is consistent with that used for SSL init
            if (sslInitialized)
            {
                throw new IllegalStateException(
                        sm.getString("aprListener.tooLateForSSLRandomSeed"));
            }

            AprLifecycleListener.SSLRandomSeed = SSLRandomSeed;
        }
    }

    public String getFIPSMode()
    {
        return FIPSMode;
    }

    public void setFIPSMode(String FIPSMode)
    {
        if (!FIPSMode.equals(AprLifecycleListener.FIPSMode))
        {
            // Ensure that the FIPS mode is consistent with that used for SSL init
            if (sslInitialized)
            {
                throw new IllegalStateException(
                        sm.getString("aprListener.tooLateForFIPSMode"));
            }

            AprLifecycleListener.FIPSMode = FIPSMode;
        }
    }

    public boolean isFIPSModeActive()
    {
        return fipsModeActive;
    }
}
