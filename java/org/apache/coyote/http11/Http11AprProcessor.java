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

package org.apache.coyote.http11;

import org.apache.coyote.*;
import org.apache.coyote.http11.filters.*;
import org.apache.tomcat.jni.*;
import org.apache.tomcat.util.buf.Ascii;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.HexUtils;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AprEndpoint;
import org.apache.tomcat.util.net.AprEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.res.StringManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


/**
 * Processes HTTP requests.
 *
 * @author Remy Maucherat
 */
public class Http11AprProcessor implements ActionHook
{


    /**
     * Logger.
     */
    protected static org.apache.juli.logging.Log log
            = org.apache.juli.logging.LogFactory.getLog(Http11AprProcessor.class);

    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);
    /**
     * Associated adapter.
     */
    protected Adapter adapter = null;


    // ----------------------------------------------------------- Constructors
    /**
     * Request object.
     */
    protected Request request = null;


    // ----------------------------------------------------- Instance Variables
    /**
     * Response object.
     */
    protected Response response = null;
    /**
     * Input.
     */
    protected InternalAprInputBuffer inputBuffer = null;
    /**
     * Output.
     */
    protected InternalAprOutputBuffer outputBuffer = null;
    /**
     * Error flag.
     */
    protected boolean error = false;
    /**
     * Keep-alive.
     */
    protected boolean keepAlive = true;
    /**
     * HTTP/1.1 flag.
     */
    protected boolean http11 = true;
    /**
     * HTTP/0.9 flag.
     */
    protected boolean http09 = false;
    /**
     * Sendfile data.
     */
    protected AprEndpoint.SendfileData sendfileData = null;
    /**
     * Comet used.
     */
    protected boolean comet = false;
    /**
     * Content delimitator for the request (if false, the connection will
     * be closed at the end of the request).
     */
    protected boolean contentDelimitation = true;
    /**
     * Is there an expectation ?
     */
    protected boolean expectation = false;
    /**
     * List of restricted user agents.
     */
    protected Pattern[] restrictedUserAgents = null;
    /**
     * Maximum number of Keep-Alive requests to honor.
     */
    protected int maxKeepAliveRequests = -1;
    /**
     * SSL enabled ?
     */
    protected boolean ssl = false;
    /**
     * Socket associated with the current connection.
     */
    protected long socket = 0;
    /**
     * Remote Address associated with the current connection.
     */
    protected String remoteAddr = null;
    /**
     * Remote Host associated with the current connection.
     */
    protected String remoteHost = null;
    /**
     * Local Host associated with the current connection.
     */
    protected String localName = null;
    /**
     * Local port to which the socket is connected
     */
    protected int localPort = -1;
    /**
     * Remote port to which the socket is connected
     */
    protected int remotePort = -1;
    /**
     * The local Host address.
     */
    protected String localAddr = null;
    /**
     * Maximum timeout on uploads. 5 minutes as in Apache HTTPD server.
     */
    protected int timeout = 300000;
    /**
     * Flag to disable setting a different time-out on uploads.
     */
    protected boolean disableUploadTimeout = false;
    /**
     * Allowed compression level.
     */
    protected int compressionLevel = 0;
    /**
     * Minimum contentsize to make compression.
     */
    protected int compressionMinSize = 2048;
    /**
     * Socket buffering.
     */
    protected int socketBuffer = -1;
    /**
     * Max save post size.
     */
    protected int maxSavePostSize = 4 * 1024;
    /**
     * List of user agents to not use gzip with
     */
    protected Pattern noCompressionUserAgents[] = null;
    /**
     * List of MIMES which could be gzipped
     */
    protected String[] compressableMimeTypes =
            {"text/html", "text/xml", "text/plain"};
    /**
     * Host name (used to avoid useless B2C conversion on the host name).
     */
    protected char[] hostNameC = new char[0];
    /**
     * Associated endpoint.
     */
    protected AprEndpoint endpoint;
    /**
     * Allow a customized the server header for the tin-foil hat folks.
     */
    protected String server = null;
    /**
     * When client certificate information is presented in a form other than
     * instances of {@link java.security.cert.X509Certificate} it needs to be
     * converted before it can be used and this property controls which JSSE
     * provider is used to perform the conversion. For example it is used with
     * the AJP connectors, the HTTP APR connector and with the
     * {@link org.apache.catalina.valves.SSLValve}. If not specified, the
     * default provider will be used.
     */
    protected String clientCertProvider = null;
    /*
     * Tracks how many internal filters are in the filter library so they
     * are skipped when looking for pluggable filters. 
     */
    private int pluggableFilterIndex = Integer.MAX_VALUE;


    public Http11AprProcessor(int headerBufferSize, AprEndpoint endpoint)
    {

        this.endpoint = endpoint;

        request = new Request();
        inputBuffer = new InternalAprInputBuffer(request, headerBufferSize);
        request.setInputBuffer(inputBuffer);

        response = new Response();
        response.setHook(this);
        outputBuffer = new InternalAprOutputBuffer(response, headerBufferSize);
        response.setOutputBuffer(outputBuffer);
        request.setResponse(response);

        ssl = endpoint.isSSLEnabled();

        initializeFilters();

        // Cause loading of HexUtils
        HexUtils.getDec('0');
    }

    // ------------------------------------------------------------- Properties

    public String getClientCertProvider()
    {
        return clientCertProvider;
    }

    public void setClientCertProvider(String s)
    {
        this.clientCertProvider = s;
    }

    /**
     * Return compression level.
     */
    public String getCompression()
    {
        switch (compressionLevel)
        {
            case 0:
                return "off";
            case 1:
                return "on";
            case 2:
                return "force";
        }
        return "off";
    }


    /**
     * Set compression level.
     */
    public void setCompression(String compression)
    {
        if (compression.equals("on"))
        {
            this.compressionLevel = 1;
        } else if (compression.equals("force"))
        {
            this.compressionLevel = 2;
        } else if (compression.equals("off"))
        {
            this.compressionLevel = 0;
        } else
        {
            try
            {
                // Try to parse compression as an int, which would give the
                // minimum compression size
                compressionMinSize = Integer.parseInt(compression);
                this.compressionLevel = 1;
            }
            catch (Exception e)
            {
                this.compressionLevel = 0;
            }
        }
    }

    /**
     * Set Minimum size to trigger compression.
     */
    public void setCompressionMinSize(int compressionMinSize)
    {
        this.compressionMinSize = compressionMinSize;
    }


    /**
     * Add user-agent for which gzip compression didn't works
     * The user agent String given will be exactly matched
     * to the user-agent header submitted by the client.
     *
     * @param userAgent user-agent string
     */
    public void addNoCompressionUserAgent(String userAgent)
    {
        try
        {
            Pattern nRule = Pattern.compile(userAgent);
            noCompressionUserAgents =
                    addREArray(noCompressionUserAgents, nRule);
        }
        catch (PatternSyntaxException pse)
        {
            log.error(sm.getString("http11processor.regexp.error", userAgent), pse);
        }
    }


    /**
     * Set no compression user agent list (this method is best when used with
     * a large number of connectors, where it would be better to have all of
     * them referenced a single array).
     */
    public void setNoCompressionUserAgents(Pattern[] noCompressionUserAgents)
    {
        this.noCompressionUserAgents = noCompressionUserAgents;
    }


    /**
     * Set no compression user agent list.
     * List contains users agents separated by ',' :
     * <p/>
     * ie: "gorilla,desesplorer,tigrus"
     */
    public void setNoCompressionUserAgents(String noCompressionUserAgents)
    {
        if (noCompressionUserAgents != null)
        {
            StringTokenizer st = new StringTokenizer(noCompressionUserAgents, ",");

            while (st.hasMoreTokens())
            {
                addNoCompressionUserAgent(st.nextToken().trim());
            }
        }
    }

    /**
     * Add a mime-type which will be compressable
     * The mime-type String will be exactly matched
     * in the response mime-type header .
     *
     * @param mimeType mime-type string
     */
    public void addCompressableMimeType(String mimeType)
    {
        compressableMimeTypes =
                addStringArray(compressableMimeTypes, mimeType);
    }


    /**
     * Set compressable mime-type list (this method is best when used with
     * a large number of connectors, where it would be better to have all of
     * them referenced a single array).
     */
    public void setCompressableMimeTypes(String[] compressableMimeTypes)
    {
        this.compressableMimeTypes = compressableMimeTypes;
    }


    /**
     * Set compressable mime-type list
     * List contains users agents separated by ',' :
     * <p/>
     * ie: "text/html,text/xml,text/plain"
     */
    public void setCompressableMimeTypes(String compressableMimeTypes)
    {
        if (compressableMimeTypes != null)
        {
            this.compressableMimeTypes = null;
            StringTokenizer st = new StringTokenizer(compressableMimeTypes, ",");

            while (st.hasMoreTokens())
            {
                addCompressableMimeType(st.nextToken().trim());
            }
        }
    }


    /**
     * Return the list of restricted user agents.
     */
    public String[] findCompressableMimeTypes()
    {
        return (compressableMimeTypes);
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Add input or output filter.
     *
     * @param className class name of the filter
     */
    protected void addFilter(String className)
    {
        try
        {
            Class clazz = Class.forName(className);
            Object obj = clazz.newInstance();
            if (obj instanceof InputFilter)
            {
                inputBuffer.addFilter((InputFilter) obj);
            } else if (obj instanceof OutputFilter)
            {
                outputBuffer.addFilter((OutputFilter) obj);
            } else
            {
                log.warn(sm.getString("http11processor.filter.unknown", className));
            }
        }
        catch (Exception e)
        {
            log.error(sm.getString("http11processor.filter.error", className), e);
        }
    }


    /**
     * General use method
     *
     * @param sArray the StringArray
     * @param value  string
     */
    private String[] addStringArray(String sArray[], String value)
    {
        String[] result = null;
        if (sArray == null)
        {
            result = new String[1];
            result[0] = value;
        } else
        {
            result = new String[sArray.length + 1];
            for (int i = 0; i < sArray.length; i++)
                result[i] = sArray[i];
            result[sArray.length] = value;
        }
        return result;
    }


    /**
     * General use method
     *
     * @param rArray the REArray
     * @param value  Obj
     */
    private Pattern[] addREArray(Pattern rArray[], Pattern value)
    {
        Pattern[] result = null;
        if (rArray == null)
        {
            result = new Pattern[1];
            result[0] = value;
        } else
        {
            result = new Pattern[rArray.length + 1];
            for (int i = 0; i < rArray.length; i++)
                result[i] = rArray[i];
            result[rArray.length] = value;
        }
        return result;
    }


    /**
     * General use method
     *
     * @param sArray the StringArray
     * @param value  string
     */
    private boolean inStringArray(String sArray[], String value)
    {
        for (int i = 0; i < sArray.length; i++)
        {
            if (sArray[i].equals(value))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Checks if any entry in the string array starts with the specified value
     *
     * @param sArray the StringArray
     * @param value  string
     */
    private boolean startsWithStringArray(String sArray[], String value)
    {
        if (value == null)
            return false;
        for (int i = 0; i < sArray.length; i++)
        {
            if (value.startsWith(sArray[i]))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Add restricted user-agent (which will downgrade the connector
     * to HTTP/1.0 mode). The user agent String given will be matched
     * via regexp to the user-agent header submitted by the client.
     *
     * @param userAgent user-agent string
     */
    public void addRestrictedUserAgent(String userAgent)
    {
        try
        {
            Pattern nRule = Pattern.compile(userAgent);
            restrictedUserAgents = addREArray(restrictedUserAgents, nRule);
        }
        catch (PatternSyntaxException pse)
        {
            log.error(sm.getString("http11processor.regexp.error", userAgent), pse);
        }
    }


    /**
     * Set restricted user agent list (this method is best when used with
     * a large number of connectors, where it would be better to have all of
     * them referenced a single array).
     */
    public void setRestrictedUserAgents(Pattern[] restrictedUserAgents)
    {
        this.restrictedUserAgents = restrictedUserAgents;
    }


    /**
     * Set restricted user agent list (which will downgrade the connector
     * to HTTP/1.0 mode). List contains users agents separated by ',' :
     * <p/>
     * ie: "gorilla,desesplorer,tigrus"
     */
    public void setRestrictedUserAgents(String restrictedUserAgents)
    {
        if (restrictedUserAgents != null)
        {
            StringTokenizer st =
                    new StringTokenizer(restrictedUserAgents, ",");
            while (st.hasMoreTokens())
            {
                addRestrictedUserAgent(st.nextToken().trim());
            }
        }
    }


    /**
     * Return the list of restricted user agents.
     */
    public String[] findRestrictedUserAgents()
    {
        String[] sarr = new String[restrictedUserAgents.length];

        for (int i = 0; i < restrictedUserAgents.length; i++)
            sarr[i] = restrictedUserAgents[i].toString();

        return (sarr);
    }

    /**
     * Return the number of Keep-Alive requests that we will honor.
     */
    public int getMaxKeepAliveRequests()
    {
        return maxKeepAliveRequests;
    }

    /**
     * Set the maximum number of Keep-Alive requests to honor.
     * This is to safeguard from DoS attacks.  Setting to a negative
     * value disables the check.
     */
    public void setMaxKeepAliveRequests(int mkar)
    {
        maxKeepAliveRequests = mkar;
    }

    /**
     * Return the maximum size of a POST which will be buffered in SSL mode.
     */
    public int getMaxSavePostSize()
    {
        return maxSavePostSize;
    }

    /**
     * Set the maximum size of a POST which will be buffered in SSL mode.
     */
    public void setMaxSavePostSize(int msps)
    {
        maxSavePostSize = msps;
    }

    /**
     * Get the flag that controls upload time-outs.
     */
    public boolean getDisableUploadTimeout()
    {
        return disableUploadTimeout;
    }

    /**
     * Set the flag to control upload time-outs.
     */
    public void setDisableUploadTimeout(boolean isDisabled)
    {
        disableUploadTimeout = isDisabled;
    }

    /**
     * Get the socket buffer flag.
     */
    public int getSocketBuffer()
    {
        return socketBuffer;
    }

    /**
     * Set the socket buffer flag.
     */
    public void setSocketBuffer(int socketBuffer)
    {
        this.socketBuffer = socketBuffer;
        outputBuffer.setSocketBuffer(socketBuffer);
    }

    /**
     * Get the upload timeout.
     */
    public int getTimeout()
    {
        return timeout;
    }

    /**
     * Set the upload timeout.
     */
    public void setTimeout(int timeouts)
    {
        timeout = timeouts;
    }

    /**
     * Get the server header name.
     */
    public String getServer()
    {
        return server;
    }

    /**
     * Set the server header name.
     */
    public void setServer(String server)
    {
        if (server == null || server.equals(""))
        {
            this.server = null;
        } else
        {
            this.server = server;
        }
    }

    /**
     * Get the request associated with this processor.
     *
     * @return The request
     */
    public Request getRequest()
    {
        return request;
    }

    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @throws IOException error during an I/O operation
     */
    public SocketState event(SocketStatus status)
            throws IOException
    {

        RequestInfo rp = request.getRequestProcessor();

        try
        {
            rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
            error = !adapter.event(request, response, status);
        }
        catch (InterruptedIOException e)
        {
            error = true;
        }
        catch (Throwable t)
        {
            log.error(sm.getString("http11processor.request.process"), t);
            // 500 - Internal Server Error
            response.setStatus(500);
            adapter.log(request, response, 0);
            error = true;
        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (error)
        {
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            recycle();
            return SocketState.CLOSED;
        } else if (!comet)
        {
            inputBuffer.nextRequest();
            outputBuffer.nextRequest();
            recycle();
            return SocketState.OPEN;
        } else
        {
            return SocketState.LONG;
        }
    }

    /**
     * Process pipelined HTTP requests using the specified input and output
     * streams.
     *
     * @throws IOException error during an I/O operation
     */
    public SocketState process(long socket)
            throws IOException
    {
        RequestInfo rp = request.getRequestProcessor();
        rp.setStage(org.apache.coyote.Constants.STAGE_PARSE);

        // Set the remote address
        remoteAddr = null;
        remoteHost = null;
        localAddr = null;
        localName = null;
        remotePort = -1;
        localPort = -1;

        // Setting up the socket
        this.socket = socket;
        inputBuffer.setSocket(socket);
        outputBuffer.setSocket(socket);

        // Error flag
        error = false;
        comet = false;
        keepAlive = true;

        int keepAliveLeft = maxKeepAliveRequests;
        long soTimeout = endpoint.getSoTimeout();

        boolean keptAlive = false;
        boolean openSocket = false;

        while (!error && keepAlive && !comet)
        {

            // Parsing the request header
            try
            {
                if (!disableUploadTimeout && keptAlive && soTimeout > 0)
                {
                    Socket.timeoutSet(socket, soTimeout * 1000);
                }
                if (!inputBuffer.parseRequestLine(keptAlive))
                {
                    // This means that no data is available right now
                    // (long keepalive), so that the processor should be recycled
                    // and the method should return true
                    openSocket = true;
                    // Add the socket to the poller
                    endpoint.getPoller().add(socket);
                    break;
                }
                request.setStartTime(System.currentTimeMillis());
                keptAlive = true;
                if (!disableUploadTimeout)
                {
                    Socket.timeoutSet(socket, timeout * 1000);
                }
                // Set this every time in case limit has been changed via JMX
                request.getMimeHeaders().setLimit(endpoint.getMaxHeaderCount());
                inputBuffer.parseHeaders();
            }
            catch (IOException e)
            {
                error = true;
                break;
            }
            catch (Throwable t)
            {
                if (log.isDebugEnabled())
                {
                    log.debug(sm.getString("http11processor.header.parse"), t);
                }
                // 400 - Bad Request
                response.setStatus(400);
                adapter.log(request, response, 0);
                error = true;
            }

            if (!error)
            {
                // Setting up filters, and parse some request headers
                rp.setStage(org.apache.coyote.Constants.STAGE_PREPARE);
                try
                {
                    prepareRequest();
                }
                catch (Throwable t)
                {
                    if (log.isDebugEnabled())
                    {
                        log.debug(sm.getString("http11processor.request.prepare"), t);
                    }
                    // 400 - Internal Server Error
                    response.setStatus(400);
                    adapter.log(request, response, 0);
                    error = true;
                }
            }

            if (maxKeepAliveRequests > 0 && --keepAliveLeft == 0)
                keepAlive = false;

            // Process the request in the adapter
            if (!error)
            {
                try
                {
                    rp.setStage(org.apache.coyote.Constants.STAGE_SERVICE);
                    adapter.service(request, response);
                    // Handle when the response was committed before a serious
                    // error occurred.  Throwing a ServletException should both
                    // set the status to 500 and set the errorException.
                    // If we fail here, then the response is likely already
                    // committed, so we can't try and set headers.
                    if (keepAlive && !error)
                    { // Avoid checking twice.
                        error = response.getErrorException() != null ||
                                statusDropsConnection(response.getStatus());
                    }
                }
                catch (InterruptedIOException e)
                {
                    error = true;
                }
                catch (Throwable t)
                {
                    log.error(sm.getString("http11processor.request.process"), t);
                    // 500 - Internal Server Error
                    response.setStatus(500);
                    adapter.log(request, response, 0);
                    error = true;
                }
            }

            // Finish the handling of the request
            if (!comet)
            {
                // If we know we are closing the connection, don't drain input.
                // This way uploading a 100GB file doesn't tie up the thread 
                // if the servlet has rejected it.
                if (error)
                    inputBuffer.setSwallowInput(false);
                endRequest();
            }

            // If there was an error, make sure the request is counted as
            // and error, and update the statistics counter
            if (error)
            {
                response.setStatus(500);
            }
            request.updateCounters();

            if (!comet)
            {
                // Next request
                inputBuffer.nextRequest();
                outputBuffer.nextRequest();
            }

            // Do sendfile as needed: add socket to sendfile and end
            if (sendfileData != null && !error)
            {
                sendfileData.socket = socket;
                sendfileData.keepAlive = keepAlive;
                if (!endpoint.getSendfile().add(sendfileData))
                {
                    if (sendfileData.socket == 0)
                    {
                        // Didn't send all the data but the socket is no longer
                        // set. Something went wrong. Close the connection.
                        // Too late to set status code.
                        if (log.isDebugEnabled())
                        {
                            log.debug(sm.getString(
                                    "http11processor.sendfile.error"));
                        }
                        error = true;
                    } else
                    {
                        openSocket = true;
                    }
                    break;
                }
            }

            rp.setStage(org.apache.coyote.Constants.STAGE_KEEPALIVE);

        }

        rp.setStage(org.apache.coyote.Constants.STAGE_ENDED);

        if (comet)
        {
            if (error)
            {
                inputBuffer.nextRequest();
                outputBuffer.nextRequest();
                recycle();
                return SocketState.CLOSED;
            } else
            {
                return SocketState.LONG;
            }
        } else
        {
            recycle();
            return (openSocket) ? SocketState.OPEN : SocketState.CLOSED;
        }

    }


    public void endRequest()
    {

        // Finish the handling of the request
        try
        {
            inputBuffer.endRequest();
        }
        catch (IOException e)
        {
            error = true;
        }
        catch (Throwable t)
        {
            log.error(sm.getString("http11processor.request.finish"), t);
            // 500 - Internal Server Error
            // Can't add a 500 to the access log since that has already been
            // written in the Adapter.service method.
            response.setStatus(500);
            error = true;
        }
        try
        {
            outputBuffer.endRequest();
        }
        catch (IOException e)
        {
            error = true;
        }
        catch (Throwable t)
        {
            log.error(sm.getString("http11processor.response.finish"), t);
            error = true;
        }

    }


    public void recycle()
    {
        inputBuffer.recycle();
        outputBuffer.recycle();
        this.socket = 0;
    }


    // ----------------------------------------------------- ActionHook Methods


    /**
     * Send an action to the connector.
     *
     * @param actionCode Type of the action
     * @param param      Action parameter
     */
    public void action(ActionCode actionCode, Object param)
    {

        if (actionCode == ActionCode.ACTION_COMMIT)
        {
            // Commit current response

            if (response.isCommitted())
                return;

            // Validate and write response headers
            prepareResponse();
            try
            {
                outputBuffer.commit();
            }
            catch (IOException e)
            {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.ACTION_ACK)
        {

            // Acknowlege request

            // Send a 100 status back if it makes sense (response not committed
            // yet, and client specified an expectation for 100-continue)

            if ((response.isCommitted()) || !expectation)
                return;

            inputBuffer.setSwallowInput(true);
            try
            {
                outputBuffer.sendAck();
            }
            catch (IOException e)
            {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.ACTION_CLIENT_FLUSH)
        {

            try
            {
                outputBuffer.flush();
            }
            catch (IOException e)
            {
                // Set error flag
                error = true;
                response.setErrorException(e);
            }

        } else if (actionCode == ActionCode.ACTION_CLOSE)
        {
            // Close

            // End the processing of the current request, and stop any further
            // transactions with the client

            comet = false;
            try
            {
                outputBuffer.endRequest();
            }
            catch (IOException e)
            {
                // Set error flag
                error = true;
            }

        } else if (actionCode == ActionCode.ACTION_RESET)
        {

            // Reset response

            // Note: This must be called before the response is committed

            outputBuffer.reset();

        } else if (actionCode == ActionCode.ACTION_CUSTOM)
        {

            // Do nothing

        } else if (actionCode == ActionCode.ACTION_REQ_HOST_ADDR_ATTRIBUTE)
        {

            // Get remote host address
            if (remoteAddr == null && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    remoteAddr = Address.getip(sa);
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteAddr().setString(remoteAddr);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCAL_NAME_ATTRIBUTE)
        {

            // Get local host name
            if (localName == null && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    localName = Address.getnameinfo(sa, 0);
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.localName().setString(localName);

        } else if (actionCode == ActionCode.ACTION_REQ_HOST_ATTRIBUTE)
        {

            // Get remote host name
            if (remoteHost == null && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    remoteHost = Address.getnameinfo(sa, 0);
                    if (remoteHost == null)
                    {
                        remoteHost = Address.getip(sa);
                    }
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.remoteHost().setString(remoteHost);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCAL_ADDR_ATTRIBUTE)
        {

            // Get local host address
            if (localAddr == null && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    localAddr = Address.getip(sa);
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }

            request.localAddr().setString(localAddr);

        } else if (actionCode == ActionCode.ACTION_REQ_REMOTEPORT_ATTRIBUTE)
        {

            // Get remote port
            if (remotePort == -1 && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_REMOTE, socket);
                    Sockaddr addr = Address.getInfo(sa);
                    remotePort = addr.port;
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setRemotePort(remotePort);

        } else if (actionCode == ActionCode.ACTION_REQ_LOCALPORT_ATTRIBUTE)
        {

            // Get local port
            if (localPort == -1 && (socket != 0))
            {
                try
                {
                    long sa = Address.get(Socket.APR_LOCAL, socket);
                    Sockaddr addr = Address.getInfo(sa);
                    localPort = addr.port;
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.info"), e);
                }
            }
            request.setLocalPort(localPort);

        } else if (actionCode == ActionCode.ACTION_REQ_SSL_ATTRIBUTE)
        {

            if (ssl && (socket != 0))
            {
                try
                {
                    // Cipher suite
                    Object sslO = SSLSocket.getInfoS(socket, SSL.SSL_INFO_CIPHER);
                    if (sslO != null)
                    {
                        request.setAttribute(AprEndpoint.CIPHER_SUITE_KEY, sslO);
                    }
                    // Get client certificate and the certificate chain if present
                    // certLength == -1 indicates an error
                    int certLength = SSLSocket.getInfoI(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                    byte[] clientCert = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT);
                    X509Certificate[] certs = null;
                    if (clientCert != null && certLength > -1)
                    {
                        certs = new X509Certificate[certLength + 1];
                        CertificateFactory cf;
                        if (clientCertProvider == null)
                        {
                            cf = CertificateFactory.getInstance("X.509");
                        } else
                        {
                            cf = CertificateFactory.getInstance("X.509",
                                    clientCertProvider);
                        }
                        certs[0] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(clientCert));
                        for (int i = 0; i < certLength; i++)
                        {
                            byte[] data = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                            certs[i + 1] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
                        }
                    }
                    if (certs != null)
                    {
                        request.setAttribute(AprEndpoint.CERTIFICATE_KEY, certs);
                    }
                    // User key size
                    sslO = new Integer(SSLSocket.getInfoI(socket, SSL.SSL_INFO_CIPHER_USEKEYSIZE));
                    if (sslO != null)
                    {
                        request.setAttribute(AprEndpoint.KEY_SIZE_KEY, sslO);
                    }
                    // SSL session ID
                    sslO = SSLSocket.getInfoS(socket, SSL.SSL_INFO_SESSION_ID);
                    if (sslO != null)
                    {
                        request.setAttribute(AprEndpoint.SESSION_ID_KEY, sslO);
                    }
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }

        } else if (actionCode == ActionCode.ACTION_REQ_SSL_CERTIFICATE)
        {

            if (ssl && (socket != 0))
            {
                // Consume and buffer the request body, so that it does not
                // interfere with the client's handshake messages
                InputFilter[] inputFilters = inputBuffer.getFilters();
                ((BufferedInputFilter) inputFilters[Constants.BUFFERED_FILTER]).setLimit(maxSavePostSize);
                inputBuffer.addActiveFilter(inputFilters[Constants.BUFFERED_FILTER]);
                try
                {
                    // Configure connection to require a certificate
                    SSLSocket.setVerify(socket, SSL.SSL_CVERIFY_REQUIRE,
                            endpoint.getSSLVerifyDepth());
                    // Renegotiate certificates
                    if (SSLSocket.renegotiate(socket) == 0)
                    {
                        // Don't look for certs unless we know renegotiation worked.
                        // Get client certificate and the certificate chain if present
                        // certLength == -1 indicates an error 
                        int certLength = SSLSocket.getInfoI(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN);
                        byte[] clientCert = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT);
                        X509Certificate[] certs = null;
                        if (clientCert != null && certLength > -1)
                        {
                            certs = new X509Certificate[certLength + 1];
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            certs[0] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(clientCert));
                            for (int i = 0; i < certLength; i++)
                            {
                                byte[] data = SSLSocket.getInfoB(socket, SSL.SSL_INFO_CLIENT_CERT_CHAIN + i);
                                certs[i + 1] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data));
                            }
                        }
                        if (certs != null)
                        {
                            request.setAttribute(AprEndpoint.CERTIFICATE_KEY, certs);
                        }
                    }
                }
                catch (Exception e)
                {
                    log.warn(sm.getString("http11processor.socket.ssl"), e);
                }
            }

        } else if (actionCode == ActionCode.ACTION_REQ_SET_BODY_REPLAY)
        {
            ByteChunk body = (ByteChunk) param;

            InputFilter savedBody = new SavedRequestInputFilter(body);
            savedBody.setRequest(request);

            InternalAprInputBuffer internalBuffer = (InternalAprInputBuffer)
                    request.getInputBuffer();
            internalBuffer.addActiveFilter(savedBody);

        } else if (actionCode == ActionCode.ACTION_AVAILABLE)
        {
            request.setAvailable(inputBuffer.available());
        } else if (actionCode == ActionCode.ACTION_COMET_BEGIN)
        {
            comet = true;
        } else if (actionCode == ActionCode.ACTION_COMET_END)
        {
            comet = false;
        } else if (actionCode == ActionCode.ACTION_COMET_CLOSE)
        {
            //no op
        } else if (actionCode == ActionCode.ACTION_COMET_SETTIMEOUT)
        {
            //no op
        }

    }


    // ------------------------------------------------------ Connector Methods

    /**
     * Get the associated adapter.
     *
     * @return the associated adapter
     */
    public Adapter getAdapter()
    {
        return adapter;
    }

    /**
     * Set the associated adapter.
     *
     * @param adapter the new adapter
     */
    public void setAdapter(Adapter adapter)
    {
        this.adapter = adapter;
    }


    // ------------------------------------------------------ Protected Methods

    /**
     * After reading the request headers, we have to setup the request filters.
     */
    protected void prepareRequest()
    {

        http11 = true;
        http09 = false;
        contentDelimitation = false;
        expectation = false;
        sendfileData = null;
        if (ssl)
        {
            request.scheme().setString("https");
        }
        MessageBytes protocolMB = request.protocol();
        if (protocolMB.equals(Constants.HTTP_11))
        {
            http11 = true;
            protocolMB.setString(Constants.HTTP_11);
        } else if (protocolMB.equals(Constants.HTTP_10))
        {
            http11 = false;
            keepAlive = false;
            protocolMB.setString(Constants.HTTP_10);
        } else if (protocolMB.equals(""))
        {
            // HTTP/0.9
            http09 = true;
            http11 = false;
            keepAlive = false;
        } else
        {
            // Unsupported protocol
            http11 = false;
            error = true;
            // Send 505; Unsupported HTTP version
            response.setStatus(505);
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals(Constants.GET))
        {
            methodMB.setString(Constants.GET);
        } else if (methodMB.equals(Constants.POST))
        {
            methodMB.setString(Constants.POST);
        }

        MimeHeaders headers = request.getMimeHeaders();

        // Check connection header
        MessageBytes connectionValueMB = headers.getValue("connection");
        if (connectionValueMB != null)
        {
            ByteChunk connectionValueBC = connectionValueMB.getByteChunk();
            if (findBytes(connectionValueBC, Constants.CLOSE_BYTES) != -1)
            {
                keepAlive = false;
            } else if (findBytes(connectionValueBC,
                    Constants.KEEPALIVE_BYTES) != -1)
            {
                keepAlive = true;
            }
        }

        MessageBytes expectMB = null;
        if (http11)
            expectMB = headers.getValue("expect");
        if ((expectMB != null)
                && (expectMB.indexOfIgnoreCase("100-continue", 0) != -1))
        {
            inputBuffer.setSwallowInput(false);
            expectation = true;
        }

        // Check user-agent header
        if ((restrictedUserAgents != null) && ((http11) || (keepAlive)))
        {
            MessageBytes userAgentValueMB = headers.getValue("user-agent");
            // Check in the restricted list, and adjust the http11
            // and keepAlive flags accordingly
            if (userAgentValueMB != null)
            {
                String userAgentValue = userAgentValueMB.toString();
                for (int i = 0; i < restrictedUserAgents.length; i++)
                {
                    if (restrictedUserAgents[i].matcher(userAgentValue).matches())
                    {
                        http11 = false;
                        keepAlive = false;
                        break;
                    }
                }
            }
        }

        // Check for a full URI (including protocol://host:port/)
        ByteChunk uriBC = request.requestURI().getByteChunk();
        if (uriBC.startsWithIgnoreCase("http", 0))
        {

            int pos = uriBC.indexOf("://", 0, 3, 4);
            int uriBCStart = uriBC.getStart();
            int slashPos = -1;
            if (pos != -1)
            {
                byte[] uriB = uriBC.getBytes();
                slashPos = uriBC.indexOf('/', pos + 3);
                if (slashPos == -1)
                {
                    slashPos = uriBC.getLength();
                    // Set URI as "/"
                    request.requestURI().setBytes
                            (uriB, uriBCStart + pos + 1, 1);
                } else
                {
                    request.requestURI().setBytes
                            (uriB, uriBCStart + slashPos,
                                    uriBC.getLength() - slashPos);
                }
                MessageBytes hostMB = headers.setValue("host");
                hostMB.setBytes(uriB, uriBCStart + pos + 3,
                        slashPos - pos - 3);
            }

        }

        // Input filter setup
        InputFilter[] inputFilters = inputBuffer.getFilters();

        // Parse transfer-encoding header
        MessageBytes transferEncodingValueMB = null;
        if (http11)
            transferEncodingValueMB = headers.getValue("transfer-encoding");
        if (transferEncodingValueMB != null)
        {
            String transferEncodingValue = transferEncodingValueMB.toString();
            // Parse the comma separated list. "identity" codings are ignored
            int startPos = 0;
            int commaPos = transferEncodingValue.indexOf(',');
            String encodingName = null;
            while (commaPos != -1)
            {
                encodingName = transferEncodingValue.substring
                        (startPos, commaPos).toLowerCase().trim();
                if (!addInputFilter(inputFilters, encodingName))
                {
                    // Unsupported transfer encoding
                    error = true;
                    // 501 - Unimplemented
                    response.setStatus(501);
                }
                startPos = commaPos + 1;
                commaPos = transferEncodingValue.indexOf(',', startPos);
            }
            encodingName = transferEncodingValue.substring(startPos)
                    .toLowerCase().trim();
            if (!addInputFilter(inputFilters, encodingName))
            {
                // Unsupported transfer encoding
                error = true;
                // 501 - Unimplemented
                response.setStatus(501);
            }
        }

        // Parse content-length header
        long contentLength = request.getContentLengthLong();
        if (contentLength >= 0)
        {
            if (contentDelimitation)
            {
                // contentDelimitation being true at this point indicates that
                // chunked encoding is being used but chunked encoding should
                // not be used with a content length. RFC 2616, section 4.4,
                // bullet 3 states Content-Length must be ignored in this case -
                // so remove it.
                headers.removeHeader("content-length");
                request.setContentLength(-1);
            } else
            {
                inputBuffer.addActiveFilter
                        (inputFilters[Constants.IDENTITY_FILTER]);
                contentDelimitation = true;
            }
        }

        MessageBytes valueMB = headers.getValue("host");

        // Check host header
        if (http11 && (valueMB == null))
        {
            error = true;
            // 400 - Bad request
            response.setStatus(400);
        }

        parseHost(valueMB);

        if (!contentDelimitation)
        {
            // If there's no content length 
            // (broken HTTP/1.0 or HTTP/1.1), assume
            // the client is not broken and didn't send a body
            inputBuffer.addActiveFilter
                    (inputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Advertise sendfile support through a request attribute
        if (endpoint.getUseSendfile())
        {
            request.setAttribute("org.apache.tomcat.sendfile.support", Boolean.TRUE);
        }
        // Advertise comet support through a request attribute
        request.setAttribute("org.apache.tomcat.comet.support", Boolean.TRUE);

        if (error)
        {
            adapter.log(request, response, 0);
        }
    }


    /**
     * Parse host.
     */
    public void parseHost(MessageBytes valueMB)
    {

        if (valueMB == null || valueMB.isNull())
        {
            // HTTP/1.0
            // Default is what the socket tells us. Overriden if a host is
            // found/parsed
            request.setServerPort(endpoint.getPort());
            return;
        }

        ByteChunk valueBC = valueMB.getByteChunk();
        byte[] valueB = valueBC.getBytes();
        int valueL = valueBC.getLength();
        int valueS = valueBC.getStart();
        int colonPos = -1;
        if (hostNameC.length < valueL)
        {
            hostNameC = new char[valueL];
        }

        boolean ipv6 = (valueB[valueS] == '[');
        boolean bracketClosed = false;
        for (int i = 0; i < valueL; i++)
        {
            char b = (char) valueB[i + valueS];
            hostNameC[i] = b;
            if (b == ']')
            {
                bracketClosed = true;
            } else if (b == ':')
            {
                if (!ipv6 || bracketClosed)
                {
                    colonPos = i;
                    break;
                }
            }
        }

        if (colonPos < 0)
        {
            if (!ssl)
            {
                // 80 - Default HTTP port
                request.setServerPort(80);
            } else
            {
                // 443 - Default HTTPS port
                request.setServerPort(443);
            }
            request.serverName().setChars(hostNameC, 0, valueL);
        } else
        {

            request.serverName().setChars(hostNameC, 0, colonPos);

            int port = 0;
            int mult = 1;
            for (int i = valueL - 1; i > colonPos; i--)
            {
                int charValue = HexUtils.getDec(valueB[i + valueS]);
                if (charValue == -1)
                {
                    // Invalid character
                    error = true;
                    // 400 - Bad request
                    response.setStatus(400);
                    break;
                }
                port = port + (charValue * mult);
                mult = 10 * mult;
            }
            request.setServerPort(port);

        }

    }


    /**
     * Check if the resource could be compressed, if the client supports it.
     */
    private boolean isCompressable()
    {

        // Check if content is not already gzipped
        MessageBytes contentEncodingMB =
                response.getMimeHeaders().getValue("Content-Encoding");

        if ((contentEncodingMB != null)
                && (contentEncodingMB.indexOf("gzip") != -1))
            return false;

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2)
            return true;

        // Check if sufficient length to trigger the compression
        long contentLength = response.getContentLengthLong();
        if ((contentLength == -1)
                || (contentLength > compressionMinSize))
        {
            // Check for compatible MIME-TYPE
            if (compressableMimeTypes != null)
            {
                return (startsWithStringArray(compressableMimeTypes,
                        response.getContentType()));
            }
        }

        return false;
    }


    /**
     * Check if compression should be used for this resource. Already checked
     * that the resource could be compressed if the client supports it.
     */
    private boolean useCompression()
    {

        // Check if browser support gzip encoding
        MessageBytes acceptEncodingMB =
                request.getMimeHeaders().getValue("accept-encoding");

        if ((acceptEncodingMB == null)
                || (acceptEncodingMB.indexOf("gzip") == -1))
            return false;

        // If force mode, always compress (test purposes only)
        if (compressionLevel == 2)
            return true;

        // Check for incompatible Browser
        if (noCompressionUserAgents != null)
        {
            MessageBytes userAgentValueMB =
                    request.getMimeHeaders().getValue("user-agent");
            if (userAgentValueMB != null)
            {
                String userAgentValue = userAgentValueMB.toString();

                // If one Regexp rule match, disable compression
                for (int i = 0; i < noCompressionUserAgents.length; i++)
                    if (noCompressionUserAgents[i].matcher(userAgentValue).matches())
                        return false;
            }
        }

        return true;
    }


    /**
     * When committing the response, we have to validate the set of headers, as
     * well as setup the response filters.
     */
    protected void prepareResponse()
    {

        boolean entityBody = true;
        contentDelimitation = false;

        OutputFilter[] outputFilters = outputBuffer.getFilters();

        if (http09 == true)
        {
            // HTTP/0.9
            outputBuffer.addActiveFilter
                    (outputFilters[Constants.IDENTITY_FILTER]);
            return;
        }

        int statusCode = response.getStatus();
        if ((statusCode == 204) || (statusCode == 205)
                || (statusCode == 304))
        {
            // No entity body
            outputBuffer.addActiveFilter
                    (outputFilters[Constants.VOID_FILTER]);
            entityBody = false;
            contentDelimitation = true;
        }

        MessageBytes methodMB = request.method();
        if (methodMB.equals("HEAD"))
        {
            // No entity body
            outputBuffer.addActiveFilter
                    (outputFilters[Constants.VOID_FILTER]);
            contentDelimitation = true;
        }

        // Sendfile support
        if (endpoint.getUseSendfile())
        {
            String fileName = (String) request.getAttribute("org.apache.tomcat.sendfile.filename");
            if (fileName != null)
            {
                // No entity body sent here
                outputBuffer.addActiveFilter
                        (outputFilters[Constants.VOID_FILTER]);
                contentDelimitation = true;
                sendfileData = new AprEndpoint.SendfileData();
                sendfileData.fileName = fileName;
                sendfileData.start =
                        ((Long) request.getAttribute("org.apache.tomcat.sendfile.start")).longValue();
                sendfileData.end =
                        ((Long) request.getAttribute("org.apache.tomcat.sendfile.end")).longValue();
            }
        }

        // Check for compression
        boolean isCompressable = false;
        boolean useCompression = false;
        if (entityBody && (compressionLevel > 0) && (sendfileData == null))
        {
            isCompressable = isCompressable();
            if (isCompressable)
            {
                useCompression = useCompression();
            }
            // Change content-length to -1 to force chunking
            if (useCompression)
            {
                response.setContentLength(-1);
            }
        }

        MimeHeaders headers = response.getMimeHeaders();
        if (!entityBody)
        {
            response.setContentLength(-1);
        } else
        {
            String contentType = response.getContentType();
            if (contentType != null)
            {
                headers.setValue("Content-Type").setString(contentType);
            }
            String contentLanguage = response.getContentLanguage();
            if (contentLanguage != null)
            {
                headers.setValue("Content-Language")
                        .setString(contentLanguage);
            }
        }

        long contentLength = response.getContentLengthLong();
        if (contentLength != -1)
        {
            headers.setValue("Content-Length").setLong(contentLength);
            outputBuffer.addActiveFilter
                    (outputFilters[Constants.IDENTITY_FILTER]);
            contentDelimitation = true;
        } else
        {
            if (entityBody && http11)
            {
                outputBuffer.addActiveFilter
                        (outputFilters[Constants.CHUNKED_FILTER]);
                contentDelimitation = true;
                headers.addValue(Constants.TRANSFERENCODING).setString(Constants.CHUNKED);
            } else
            {
                outputBuffer.addActiveFilter
                        (outputFilters[Constants.IDENTITY_FILTER]);
            }
        }

        if (useCompression)
        {
            outputBuffer.addActiveFilter(outputFilters[Constants.GZIP_FILTER]);
            headers.setValue("Content-Encoding").setString("gzip");
        }
        // If it might be compressed, set the Vary header
        if (isCompressable)
        {
            // Make Proxies happy via Vary (from mod_deflate)
            MessageBytes vary = headers.getValue("Vary");
            if (vary == null)
            {
                // Add a new Vary header
                headers.setValue("Vary").setString("Accept-Encoding");
            } else if (vary.equals("*"))
            {
                // No action required
            } else
            {
                // Merge into current header
                headers.setValue("Vary").setString(
                        vary.getString() + ",Accept-Encoding");
            }
        }

        // Add date header unless application has already set one (e.g. in a
        // Caching Filter)
        if (headers.getValue("Date") == null)
        {
            headers.setValue("Date").setString(
                    FastHttpDateFormat.getCurrentDate());
        }

        // FIXME: Add transfer encoding header

        if ((entityBody) && (!contentDelimitation))
        {
            // Mark as close the connection after the request, and add the
            // connection: close header
            keepAlive = false;
        }

        // If we know that the request is bad this early, add the
        // Connection: close header.
        keepAlive = keepAlive && !statusDropsConnection(statusCode);
        if (!keepAlive)
        {
            headers.addValue(Constants.CONNECTION).setString(Constants.CLOSE);
        } else if (!http11 && !error)
        {
            headers.addValue(Constants.CONNECTION).setString(Constants.KEEPALIVE);
        }

        // Build the response header
        outputBuffer.sendStatus();

        // Add server header
        if (server != null)
        {
            // Always overrides anything the app might set
            headers.setValue("Server").setString(server);
        } else if (headers.getValue("Server") == null)
        {
            // If app didn't set the header, use the default
            outputBuffer.write(Constants.SERVER_BYTES);
        }

        int size = headers.size();
        for (int i = 0; i < size; i++)
        {
            outputBuffer.sendHeader(headers.getName(i), headers.getValue(i));
        }
        outputBuffer.endHeaders();

    }


    /**
     * Initialize standard input and output filters.
     */
    protected void initializeFilters()
    {

        // Create and add the identity filters.
        inputBuffer.addFilter(new IdentityInputFilter());
        outputBuffer.addFilter(new IdentityOutputFilter());

        // Create and add the chunked filters.
        inputBuffer.addFilter(new ChunkedInputFilter());
        outputBuffer.addFilter(new ChunkedOutputFilter());

        // Create and add the void filters.
        inputBuffer.addFilter(new VoidInputFilter());
        outputBuffer.addFilter(new VoidOutputFilter());

        // Create and add buffered input filter
        inputBuffer.addFilter(new BufferedInputFilter());

        // Create and add the chunked filters.
        //inputBuffer.addFilter(new GzipInputFilter());
        outputBuffer.addFilter(new GzipOutputFilter());

        pluggableFilterIndex = inputBuffer.filterLibrary.length;

    }


    /**
     * Add an input filter to the current request.
     *
     * @return false if the encoding was not found (which would mean it is
     * unsupported)
     */
    protected boolean addInputFilter(InputFilter[] inputFilters,
                                     String encodingName)
    {
        if (encodingName.equals("identity"))
        {
            // Skip
        } else if (encodingName.equals("chunked"))
        {
            inputBuffer.addActiveFilter
                    (inputFilters[Constants.CHUNKED_FILTER]);
            contentDelimitation = true;
        } else
        {
            for (int i = pluggableFilterIndex; i < inputFilters.length; i++)
            {
                if (inputFilters[i].getEncodingName()
                        .toString().equals(encodingName))
                {
                    inputBuffer.addActiveFilter(inputFilters[i]);
                    return true;
                }
            }
            return false;
        }
        return true;
    }


    /**
     * Specialized utility method: find a sequence of lower case bytes inside
     * a ByteChunk.
     */
    protected int findBytes(ByteChunk bc, byte[] b)
    {

        byte first = b[0];
        byte[] buff = bc.getBuffer();
        int start = bc.getStart();
        int end = bc.getEnd();

        // Look for first char
        int srcEnd = b.length;

        for (int i = start; i <= (end - srcEnd); i++)
        {
            if (Ascii.toLower(buff[i]) != first) continue;
            // found first char, now look for a match
            int myPos = i + 1;
            for (int srcPos = 1; srcPos < srcEnd; )
            {
                if (Ascii.toLower(buff[myPos++]) != b[srcPos++])
                    break;
                if (srcPos == srcEnd) return i - start; // found it
            }
        }
        return -1;

    }

    /**
     * Determine if we must drop the connection because of the HTTP status
     * code.  Use the same list of codes as Apache/httpd.
     */
    protected boolean statusDropsConnection(int status)
    {
        return status == 400 /* SC_BAD_REQUEST */ ||
                status == 408 /* SC_REQUEST_TIMEOUT */ ||
                status == 411 /* SC_LENGTH_REQUIRED */ ||
                status == 413 /* SC_REQUEST_ENTITY_TOO_LARGE */ ||
                status == 414 /* SC_REQUEST_URI_TOO_LARGE */ ||
                status == 500 /* SC_INTERNAL_SERVER_ERROR */ ||
                status == 503 /* SC_SERVICE_UNAVAILABLE */ ||
                status == 501 /* SC_NOT_IMPLEMENTED */;
    }

}
