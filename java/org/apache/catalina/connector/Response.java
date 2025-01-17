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


package org.apache.catalina.connector;


import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.Session;
import org.apache.catalina.Wrapper;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.CharsetMapper;
import org.apache.catalina.util.DateTool;
import org.apache.catalina.util.StringManager;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.UEncoder;
import org.apache.tomcat.util.http.FastHttpDateFormat;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.http.ServerCookie;
import org.apache.tomcat.util.net.URL;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Wrapper object for the Coyote response.
 *
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 */

public class Response
        implements HttpServletResponse
{


    // ----------------------------------------------------------- Constructors

    /**
     * Descriptive information about this Response implementation.
     */
    protected static final String info =
            "org.apache.coyote.tomcat5.CoyoteResponse/1.0";
    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);


    // ----------------------------------------------------- Class Variables

    static
    {
        // Ensure that URL is loaded for SM
        URL.isSchemeChar('c');
    }

    /**
     * The date format we will use for creating date headers.
     */
    protected SimpleDateFormat format = null;


    // ----------------------------------------------------- Instance Variables
    /**
     * Associated Catalina connector.
     */
    protected Connector connector;


    // ------------------------------------------------------------- Properties
    /**
     * Coyote response.
     */
    protected org.apache.coyote.Response coyoteResponse;
    /**
     * The associated output buffer.
     */
    protected OutputBuffer outputBuffer;
    /**
     * The associated output stream.
     */
    protected CoyoteOutputStream outputStream;
    /**
     * The associated writer.
     */
    protected CoyoteWriter writer;
    /**
     * The application commit flag.
     */
    protected boolean appCommitted = false;
    /**
     * The included flag.
     */
    protected boolean included = false;
    /**
     * The error flag.
     */
    protected boolean error = false;
    /**
     * The set of Cookies associated with this Response.
     */
    protected ArrayList<Cookie> cookies = new ArrayList<Cookie>();
    /**
     * Using output stream flag.
     */
    protected boolean usingOutputStream = false;
    /**
     * Using writer flag.
     */
    protected boolean usingWriter = false;
    /**
     * URL encoder.
     */
    protected UEncoder urlEncoder = new UEncoder();
    /**
     * Recyclable buffer to hold the redirect URL.
     */
    protected CharChunk redirectURLCC = new CharChunk();
    /**
     * The request with which this response is associated.
     */
    protected Request request = null;
    /**
     * The facade associated with this response.
     */
    protected ResponseFacade facade = null;
    /**
     * The characterEncoding flag
     */
    private boolean isCharacterEncodingSet = false;


    public Response()
    {
        urlEncoder.addSafeCharacter('/');
    }

    /**
     * Return the Connector through which this Request was received.
     */
    public Connector getConnector()
    {
        return (this.connector);
    }

    /**
     * Set the Connector through which this Request was received.
     *
     * @param connector The new connector
     */
    public void setConnector(Connector connector)
    {
        this.connector = connector;
        if ("AJP/1.3".equals(connector.getProtocol()))
        {
            // default size to size of one ajp-packet
            outputBuffer = new OutputBuffer(8184);
        } else
        {
            outputBuffer = new OutputBuffer();
        }
        outputStream = new CoyoteOutputStream(outputBuffer);
        writer = new CoyoteWriter(outputBuffer);
    }

    /**
     * Get the Coyote response.
     */
    public org.apache.coyote.Response getCoyoteResponse()
    {
        return (coyoteResponse);
    }

    /**
     * Set the Coyote response.
     *
     * @param coyoteResponse The Coyote response
     */
    public void setCoyoteResponse(org.apache.coyote.Response coyoteResponse)
    {
        this.coyoteResponse = coyoteResponse;
        outputBuffer.setResponse(coyoteResponse);
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Return the Context within which this Request is being processed.
     */
    public Context getContext()
    {
        return (request.getContext());
    }

    /**
     * Set the Context within which this Request is being processed.  This
     * must be called as soon as the appropriate Context is identified, because
     * it identifies the value to be returned by <code>getContextPath()</code>,
     * and thus enables parsing of the request URI.
     *
     * @param context The newly associated Context
     */
    public void setContext(Context context)
    {
        request.setContext(context);
    }


    // ------------------------------------------------------- Response Methods

    /**
     * Release all object references, and initialize instance variables, in
     * preparation for reuse of this object.
     */
    public void recycle()
    {

        outputBuffer.recycle();
        usingOutputStream = false;
        usingWriter = false;
        appCommitted = false;
        included = false;
        error = false;
        isCharacterEncodingSet = false;

        cookies.clear();

        if (Globals.IS_SECURITY_ENABLED || Connector.RECYCLE_FACADES)
        {
            if (facade != null)
            {
                facade.clear();
                facade = null;
            }
            if (outputStream != null)
            {
                outputStream.clear();
                outputStream = null;
            }
            if (writer != null)
            {
                writer.clear();
                writer = null;
            }
        } else
        {
            writer.recycle();
        }

    }

    /**
     * Clear cached encoders (to save memory for Comet requests).
     */
    public void clearEncoders()
    {
        outputBuffer.clearEncoders();
    }

    /**
     * Return the number of bytes actually written to the output stream.
     */
    public int getContentCount()
    {
        return outputBuffer.getContentWritten();
    }

    /**
     * Return the number of bytes actually written to the output stream.
     */
    public long getContentCountLong()
    {
        return outputBuffer.getContentWrittenLong();
    }

    /**
     * Application commit flag accessor.
     */
    public boolean isAppCommitted()
    {
        return (this.appCommitted || isCommitted() || isSuspended()
                || ((getContentLength() > 0)
                && (getContentCount() >= getContentLength())));
    }

    /**
     * Set the application commit flag.
     *
     * @param appCommitted The new application committed flag value
     */
    public void setAppCommitted(boolean appCommitted)
    {
        this.appCommitted = appCommitted;
    }

    /**
     * Return the "processing inside an include" flag.
     */
    public boolean getIncluded()
    {
        return included;
    }

    /**
     * Set the "processing inside an include" flag.
     *
     * @param included <code>true</code> if we are currently inside a
     *                 RequestDispatcher.include(), else <code>false</code>
     */
    public void setIncluded(boolean included)
    {
        this.included = included;
    }

    /**
     * Return descriptive information about this Response implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    public String getInfo()
    {
        return (info);
    }

    /**
     * Return the Request with which this Response is associated.
     */
    public org.apache.catalina.connector.Request getRequest()
    {
        return (this.request);
    }

    /**
     * Set the Request with which this Response is associated.
     *
     * @param request The new associated request
     */
    public void setRequest(org.apache.catalina.connector.Request request)
    {
        this.request = (Request) request;
    }

    /**
     * Return the <code>ServletResponse</code> for which this object
     * is the facade.
     */
    public HttpServletResponse getResponse()
    {
        if (facade == null)
        {
            facade = new ResponseFacade(this);
        }
        return (facade);
    }


    /**
     * Return the output stream associated with this Response.
     */
    public OutputStream getStream()
    {
        if (outputStream == null)
        {
            outputStream = new CoyoteOutputStream(outputBuffer);
        }
        return outputStream;
    }


    /**
     * Set the output stream associated with this Response.
     *
     * @param stream The new output stream
     */
    public void setStream(OutputStream stream)
    {
        // This method is evil
    }

    /**
     * Suspended flag accessor.
     */
    public boolean isSuspended()
    {
        return outputBuffer.isSuspended();
    }

    /**
     * Set the suspended flag.
     *
     * @param suspended The new suspended flag value
     */
    public void setSuspended(boolean suspended)
    {
        outputBuffer.setSuspended(suspended);
    }

    /**
     * Closed flag accessor.
     */
    public boolean isClosed()
    {
        return outputBuffer.isClosed();
    }


    /**
     * Set the error flag.
     */
    public void setError()
    {
        error = true;
    }


    /**
     * Error flag accessor.
     */
    public boolean isError()
    {
        return error;
    }


    /**
     * Create and return a ServletOutputStream to write the content
     * associated with this Response.
     *
     * @throws IOException if an input/output error occurs
     */
    public ServletOutputStream createOutputStream()
            throws IOException
    {
        // Probably useless
        if (outputStream == null)
        {
            outputStream = new CoyoteOutputStream(outputBuffer);
        }
        return outputStream;
    }


    /**
     * Perform whatever actions are required to flush and close the output
     * stream or writer, in a single operation.
     *
     * @throws IOException if an input/output error occurs
     */
    public void finishResponse()
            throws IOException
    {
        // Writing leftover bytes
        outputBuffer.close();
    }


    /**
     * Return the content length that was set or calculated for this Response.
     */
    public int getContentLength()
    {
        return (coyoteResponse.getContentLength());
    }

    /**
     * Set the content length (in bytes) for this Response.
     *
     * @param length The new content length
     */
    public void setContentLength(int length)
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        if (usingWriter)
            return;

        coyoteResponse.setContentLength(length);

    }

    /**
     * Return the content type that was set or calculated for this response,
     * or <code>null</code> if no content type was set.
     */
    public String getContentType()
    {
        return (coyoteResponse.getContentType());
    }


    // ------------------------------------------------ ServletResponse Methods

    /**
     * Set the content type for this Response.
     *
     * @param type The new content type
     */
    public void setContentType(String type)
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        // Ignore charset if getWriter() has already been called
        if (usingWriter)
        {
            if (type != null)
            {
                int index = type.indexOf(";");
                if (index != -1)
                {
                    type = type.substring(0, index);
                }
            }
        }

        coyoteResponse.setContentType(type);

        // Check to see if content type contains charset
        if (type != null)
        {
            int index = type.indexOf(";");
            if (index != -1)
            {
                int len = type.length();
                index++;
                while (index < len && Character.isSpace(type.charAt(index)))
                {
                    index++;
                }
                if (index + 7 < len
                        && type.charAt(index) == 'c'
                        && type.charAt(index + 1) == 'h'
                        && type.charAt(index + 2) == 'a'
                        && type.charAt(index + 3) == 'r'
                        && type.charAt(index + 4) == 's'
                        && type.charAt(index + 5) == 'e'
                        && type.charAt(index + 6) == 't'
                        && type.charAt(index + 7) == '=')
                {
                    isCharacterEncodingSet = true;
                }
            }
        }
    }

    /**
     * Return a PrintWriter that can be used to render error messages,
     * regardless of whether a stream or writer has already been acquired.
     *
     * @return Writer which can be used for error reports. If the response is
     * not an error report returned using sendError or triggered by an
     * unexpected exception thrown during the servlet processing
     * (and only in that case), null will be returned if the response stream
     * has already been used.
     * @throws IOException if an input/output error occurs
     */
    public PrintWriter getReporter() throws IOException
    {
        if (outputBuffer.isNew())
        {
            outputBuffer.checkConverter();
            if (writer == null)
            {
                writer = new CoyoteWriter(outputBuffer);
            }
            return writer;
        } else
        {
            return null;
        }
    }

    /**
     * Flush the buffer and commit this response.
     *
     * @throws IOException if an input/output error occurs
     */
    public void flushBuffer()
            throws IOException
    {
        outputBuffer.flush();
    }

    /**
     * Return the actual buffer size used for this Response.
     */
    public int getBufferSize()
    {
        return outputBuffer.getBufferSize();
    }

    /**
     * Set the buffer size to be used for this Response.
     *
     * @param size The new buffer size
     * @throws IllegalStateException if this method is called after
     *                               output has been committed for this response
     */
    public void setBufferSize(int size)
    {

        if (isCommitted() || !outputBuffer.isNew())
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.setBufferSize.ise"));

        outputBuffer.setBufferSize(size);

    }

    /**
     * Return the character encoding used for this Response.
     */
    public String getCharacterEncoding()
    {
        return (coyoteResponse.getCharacterEncoding());
    }

    /*
     * Overrides the name of the character encoding used in the body
     * of the request. This method must be called prior to reading
     * request parameters or reading input using getReader().
     *
     * @param charset String containing the name of the chararacter encoding.
     */
    public void setCharacterEncoding(String charset)
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        // Ignore any call made after the getWriter has been invoked
        // The default should be used
        if (usingWriter)
            return;

        coyoteResponse.setCharacterEncoding(charset);
        isCharacterEncodingSet = true;
    }

    /**
     * Return the servlet output stream associated with this Response.
     *
     * @throws IllegalStateException if <code>getWriter</code> has
     *                               already been called for this response
     * @throws IOException           if an input/output error occurs
     */
    public ServletOutputStream getOutputStream()
            throws IOException
    {

        if (usingWriter)
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.getOutputStream.ise"));

        usingOutputStream = true;
        if (outputStream == null)
        {
            outputStream = new CoyoteOutputStream(outputBuffer);
        }
        return outputStream;

    }

    /**
     * Return the Locale assigned to this response.
     */
    public Locale getLocale()
    {
        return (coyoteResponse.getLocale());
    }

    /**
     * Set the Locale that is appropriate for this response, including
     * setting the appropriate character encoding.
     *
     * @param locale The new locale
     */
    public void setLocale(Locale locale)
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        coyoteResponse.setLocale(locale);

        // Ignore any call made after the getWriter has been invoked.
        // The default should be used
        if (usingWriter)
            return;

        if (isCharacterEncodingSet)
        {
            return;
        }

        CharsetMapper cm = getContext().getCharsetMapper();
        String charset = cm.getCharset(locale);
        if (charset != null)
        {
            coyoteResponse.setCharacterEncoding(charset);
        }

    }

    /**
     * Return the writer associated with this Response.
     *
     * @throws IllegalStateException if <code>getOutputStream</code> has
     *                               already been called for this response
     * @throws IOException           if an input/output error occurs
     */
    public PrintWriter getWriter()
            throws IOException
    {

        if (usingOutputStream)
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.getWriter.ise"));

        if (Globals.STRICT_SERVLET_COMPLIANCE)
        {
            /*
             * If the response's character encoding has not been specified as
             * described in <code>getCharacterEncoding</code> (i.e., the method
             * just returns the default value <code>ISO-8859-1</code>),
             * <code>getWriter</code> updates it to <code>ISO-8859-1</code>
             * (with the effect that a subsequent call to getContentType() will
             * include a charset=ISO-8859-1 component which will also be
             * reflected in the Content-Type response header, thereby satisfying
             * the Servlet spec requirement that containers must communicate the
             * character encoding used for the servlet response's writer to the
             * client).
             */
            setCharacterEncoding(getCharacterEncoding());
        }

        usingWriter = true;
        outputBuffer.checkConverter();
        if (writer == null)
        {
            writer = new CoyoteWriter(outputBuffer);
        }
        return writer;

    }

    /**
     * Has the output of this response already been committed?
     */
    public boolean isCommitted()
    {
        return (coyoteResponse.isCommitted());
    }

    /**
     * Clear any content written to the buffer.
     *
     * @throws IllegalStateException if this response has already
     *                               been committed
     */
    public void reset()
    {

        if (included)
            return;     // Ignore any call from an included servlet

        coyoteResponse.reset();
        outputBuffer.reset();
        usingOutputStream = false;
        usingWriter = false;
        isCharacterEncodingSet = false;
    }

    /**
     * Reset the data buffer but not any status or header information.
     *
     * @throws IllegalStateException if the response has already
     *                               been committed
     */
    public void resetBuffer()
    {
        resetBuffer(false);
    }

    /**
     * Reset the data buffer and the using Writer/Stream flags but not any
     * status or header information.
     *
     * @param resetWriterStreamFlags <code>true</code> if the internal
     *                               <code>usingWriter</code>, <code>usingOutputStream</code>,
     *                               <code>isCharacterEncodingSet</code> flags should also be reset
     * @throws IllegalStateException if the response has already
     *                               been committed
     */
    public void resetBuffer(boolean resetWriterStreamFlags)
    {

        if (isCommitted())
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.resetBuffer.ise"));

        outputBuffer.reset();

        if (resetWriterStreamFlags)
        {
            usingOutputStream = false;
            usingWriter = false;
            isCharacterEncodingSet = false;
        }

    }


    // --------------------------------------------------- HttpResponse Methods

    /**
     * Return an array of all cookies set for this response, or
     * a zero-length array if no cookies have been set.
     */
    public Cookie[] getCookies()
    {
        return cookies.toArray(new Cookie[cookies.size()]);
    }


    /**
     * Return the value for the specified header, or <code>null</code> if this
     * header has not been set.  If more than one value was added for this
     * name, only the first is returned; use getHeaderValues() to retrieve all
     * of them.
     *
     * @param name Header name to look up
     */
    public String getHeader(String name)
    {
        return coyoteResponse.getMimeHeaders().getHeader(name);
    }


    /**
     * Return an array of all the header names set for this response, or
     * a zero-length array if no headers have been set.
     */
    public String[] getHeaderNames()
    {

        MimeHeaders headers = coyoteResponse.getMimeHeaders();
        int n = headers.size();
        String[] result = new String[n];
        for (int i = 0; i < n; i++)
        {
            result[i] = headers.getName(i).toString();
        }
        return result;

    }


    /**
     * Return an array of all the header values associated with the
     * specified header name, or an zero-length array if there are no such
     * header values.
     *
     * @param name Header name to look up
     */
    public String[] getHeaderValues(String name)
    {

        Enumeration enumeration = coyoteResponse.getMimeHeaders().values(name);
        Vector result = new Vector();
        while (enumeration.hasMoreElements())
        {
            result.addElement(enumeration.nextElement());
        }
        String[] resultArray = new String[result.size()];
        result.copyInto(resultArray);
        return resultArray;

    }


    /**
     * Return the error message that was set with <code>sendError()</code>
     * for this Response.
     */
    public String getMessage()
    {
        return coyoteResponse.getMessage();
    }


    /**
     * Return the HTTP status code associated with this Response.
     */
    public int getStatus()
    {
        return coyoteResponse.getStatus();
    }

    /**
     * Set the HTTP status to be returned with this response.
     *
     * @param status The new HTTP status
     */
    public void setStatus(int status)
    {
        setStatus(status, null);
    }


    // -------------------------------------------- HttpServletResponse Methods

    /**
     * Reset this response, and specify the values for the HTTP status code
     * and corresponding message.
     *
     * @throws IllegalStateException if this response has already been
     *                               committed
     */
    public void reset(int status, String message)
    {
        reset();
        setStatus(status, message);
    }

    /**
     * Add the specified Cookie to those that will be included with
     * this Response.
     *
     * @param cookie Cookie to be added
     */
    public void addCookie(final Cookie cookie)
    {

        // Ignore any call from an included servlet
        if (included)
            return;

        addCookieInternal(cookie);

    }

    /**
     * Special method for adding a session cookie as we should be overriding
     * any previous
     *
     * @param cookie
     */
    public void addSessionCookieInternal(final Cookie cookie,
                                         boolean httpOnly)
    {
        if (isCommitted())
            return;

        String name = cookie.getName();
        final String headername = "Set-Cookie";
        final String startsWith = name + "=";
        final StringBuffer sb = generateCookieString(cookie, httpOnly);
        boolean set = false;
        MimeHeaders headers = coyoteResponse.getMimeHeaders();
        int n = headers.size();
        for (int i = 0; i < n; i++)
        {
            if (headers.getName(i).toString().equals(headername))
            {
                if (headers.getValue(i).toString().startsWith(startsWith))
                {
                    headers.getValue(i).setString(sb.toString());
                    set = true;
                }
            }
        }
        if (set)
        {
            Iterator<Cookie> iter = cookies.iterator();
            while (iter.hasNext())
            {
                Cookie c = iter.next();
                if (name.equals(c.getName()))
                {
                    iter.remove();
                    break;
                }
            }
        } else
        {
            addHeader(headername, sb.toString());
        }
        cookies.add(cookie);


    }

    /**
     * Add the specified Cookie to those that will be included with
     * this Response.
     *
     * @param cookie Cookie to be added
     */
    public void addCookieInternal(final Cookie cookie)
    {
        addCookieInternal(cookie, false);
    }

    /**
     * Add the specified Cookie to those that will be included with
     * this Response.
     *
     * @param cookie   Cookie to be added
     * @param httpOnly Should the httpOnly falg be set on this cookie
     */
    public void addCookieInternal(final Cookie cookie, final boolean httpOnly)
    {

        if (isCommitted())
            return;

        final StringBuffer sb = generateCookieString(cookie, httpOnly);
        //if we reached here, no exception, cookie is valid
        // the header name is Set-Cookie for both "old" and v.1 ( RFC2109 )
        // RFC2965 is not supported by browsers and the Servlet spec
        // asks for 2109.
        addHeader("Set-Cookie", sb.toString());

        cookies.add(cookie);
    }

    public StringBuffer generateCookieString(final Cookie cookie,
                                             final boolean httpOnly)
    {
        final StringBuffer sb = new StringBuffer();
        //web application code can receive a IllegalArgumentException 
        //from the appendCookieValue invokation
        if (SecurityUtil.isPackageProtectionEnabled())
        {
            AccessController.doPrivileged(new PrivilegedAction()
            {
                public Object run()
                {
                    ServerCookie.appendCookieValue
                            (sb, cookie.getVersion(), cookie.getName(),
                                    cookie.getValue(), cookie.getPath(),
                                    cookie.getDomain(), cookie.getComment(),
                                    cookie.getMaxAge(), cookie.getSecure(),
                                    httpOnly);
                    return null;
                }
            });
        } else
        {
            ServerCookie.appendCookieValue
                    (sb, cookie.getVersion(), cookie.getName(), cookie.getValue(),
                            cookie.getPath(), cookie.getDomain(), cookie.getComment(),
                            cookie.getMaxAge(), cookie.getSecure(), httpOnly);
        }
        return sb;
    }

    /**
     * Add the specified date header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Date value to be set
     */
    public void addDateHeader(String name, long value)
    {

        if (name == null || name.length() == 0)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
        {
            return;
        }

        if (format == null)
        {
            format = new SimpleDateFormat(DateTool.HTTP_RESPONSE_DATE_HEADER,
                    Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
        }

        addHeader(name, FastHttpDateFormat.formatDate(value, format));

    }

    /**
     * Add the specified header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Value to be set
     */
    public void addHeader(String name, String value)
    {

        if (name == null || name.length() == 0 || value == null)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        coyoteResponse.addHeader(name, value);

    }

    /**
     * Add the specified integer header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Integer value to be set
     */
    public void addIntHeader(String name, int value)
    {

        if (name == null || name.length() == 0)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        addHeader(name, "" + value);

    }

    /**
     * Has the specified header been set already in this response?
     *
     * @param name Name of the header to check
     */
    public boolean containsHeader(String name)
    {
        // Need special handling for Content-Type and Content-Length due to
        // special handling of these in coyoteResponse
        char cc = name.charAt(0);
        if (cc == 'C' || cc == 'c')
        {
            if (name.equalsIgnoreCase("Content-Type"))
            {
                // Will return null if this has not been set
                return (coyoteResponse.getContentType() != null);
            }
            if (name.equalsIgnoreCase("Content-Length"))
            {
                // -1 means not known and is not sent to client
                return (coyoteResponse.getContentLengthLong() != -1);
            }
        }

        return coyoteResponse.containsHeader(name);
    }

    /**
     * Encode the session identifier associated with this response
     * into the specified redirect URL, if necessary.
     *
     * @param url URL to be encoded
     */
    public String encodeRedirectURL(String url)
    {

        if (isEncodeable(toAbsolute(url)))
        {
            return (toEncoded(url, request.getSessionInternal().getIdInternal()));
        } else
        {
            return (url);
        }

    }

    /**
     * Encode the session identifier associated with this response
     * into the specified redirect URL, if necessary.
     *
     * @param url URL to be encoded
     * @deprecated As of Version 2.1 of the Java Servlet API, use
     * <code>encodeRedirectURL()</code> instead.
     */
    public String encodeRedirectUrl(String url)
    {
        return (encodeRedirectURL(url));
    }

    /**
     * Encode the session identifier associated with this response
     * into the specified URL, if necessary.
     *
     * @param url URL to be encoded
     */
    public String encodeURL(String url)
    {

        String absolute = toAbsolute(url);
        if (isEncodeable(absolute))
        {
            // W3c spec clearly said 
            if (url.equalsIgnoreCase(""))
            {
                url = absolute;
            }
            return (toEncoded(url, request.getSessionInternal().getIdInternal()));
        } else
        {
            return (url);
        }

    }

    /**
     * Encode the session identifier associated with this response
     * into the specified URL, if necessary.
     *
     * @param url URL to be encoded
     * @deprecated As of Version 2.1 of the Java Servlet API, use
     * <code>encodeURL()</code> instead.
     */
    public String encodeUrl(String url)
    {
        return (encodeURL(url));
    }

    /**
     * Send an acknowledgment of a request.
     *
     * @throws IOException if an input/output error occurs
     */
    public void sendAcknowledgement()
            throws IOException
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        coyoteResponse.acknowledge();

    }

    /**
     * Send an error response with the specified status and a
     * default message.
     *
     * @param status HTTP status code to send
     * @throws IllegalStateException if this response has
     *                               already been committed
     * @throws IOException           if an input/output error occurs
     */
    public void sendError(int status)
            throws IOException
    {
        sendError(status, null);
    }

    /**
     * Send an error response with the specified status and message.
     *
     * @param status  HTTP status code to send
     * @param message Corresponding message to send
     * @throws IllegalStateException if this response has
     *                               already been committed
     * @throws IOException           if an input/output error occurs
     */
    public void sendError(int status, String message)
            throws IOException
    {

        if (isCommitted())
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.sendError.ise"));

        // Ignore any call from an included servlet
        if (included)
            return;

        Wrapper wrapper = getRequest().getWrapper();
        if (wrapper != null)
        {
            wrapper.incrementErrorCount();
        }

        setError();

        coyoteResponse.setStatus(status);
        coyoteResponse.setMessage(message);

        // Clear any data content that has been buffered
        resetBuffer();

        // Cause the response to be finished (from the application perspective)
        setSuspended(true);

    }

    /**
     * Send a temporary redirect to the specified redirect location URL.
     *
     * @param location Location URL to redirect to
     * @throws IllegalStateException if this response has
     *                               already been committed
     * @throws IOException           if an input/output error occurs
     */
    public void sendRedirect(String location)
            throws IOException
    {

        if (isCommitted())
            throw new IllegalStateException
                    (sm.getString("coyoteResponse.sendRedirect.ise"));

        // Ignore any call from an included servlet
        if (included)
            return;

        // Clear any data content that has been buffered
        resetBuffer();

        // Generate a temporary redirect to the specified location
        try
        {
            String absolute = toAbsolute(location);
            setStatus(SC_FOUND);
            setHeader("Location", absolute);
        }
        catch (IllegalArgumentException e)
        {
            setStatus(SC_NOT_FOUND);
        }

        // Cause the response to be finished (from the application perspective)
        setSuspended(true);

    }

    /**
     * Set the specified date header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Date value to be set
     */
    public void setDateHeader(String name, long value)
    {

        if (name == null || name.length() == 0)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
        {
            return;
        }

        if (format == null)
        {
            format = new SimpleDateFormat(DateTool.HTTP_RESPONSE_DATE_HEADER,
                    Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
        }

        setHeader(name, FastHttpDateFormat.formatDate(value, format));

    }

    /**
     * Set the specified header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Value to be set
     */
    public void setHeader(String name, String value)
    {

        if (name == null || name.length() == 0 || value == null)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        coyoteResponse.setHeader(name, value);

    }

    /**
     * Set the specified integer header to the specified value.
     *
     * @param name  Name of the header to set
     * @param value Integer value to be set
     */
    public void setIntHeader(String name, int value)
    {

        if (name == null || name.length() == 0)
        {
            return;
        }

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        setHeader(name, "" + value);

    }

    /**
     * Set the HTTP status and message to be returned with this response.
     *
     * @param status  The new HTTP status
     * @param message The associated text message
     * @deprecated As of Version 2.1 of the Java Servlet API, this method
     * has been deprecated due to the ambiguous meaning of the message
     * parameter.
     */
    public void setStatus(int status, String message)
    {

        if (isCommitted())
            return;

        // Ignore any call from an included servlet
        if (included)
            return;

        coyoteResponse.setStatus(status);
        coyoteResponse.setMessage(message);

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Return <code>true</code> if the specified URL should be encoded with
     * a session identifier.  This will be true if all of the following
     * conditions are met:
     * <ul>
     * <li>The request we are responding to asked for a valid session
     * <li>The requested session ID was not received via a cookie
     * <li>The specified URL points back to somewhere within the web
     * application that is responding to this request
     * <li>If URL rewriting hasn't been disabled for this context
     * </ul>
     *
     * @param location Absolute URL to be validated
     */
    protected boolean isEncodeable(final String location)
    {
        if (getContext().isDisableURLRewriting())
            return (false);
        if (location == null)
            return (false);

        // Is this an intra-document reference?
        if (location.startsWith("#"))
            return (false);

        // Are we in a valid session that is not using cookies?
        final Request hreq = request;
        final Session session = hreq.getSessionInternal(false);
        if (session == null)
            return (false);
        if (hreq.isRequestedSessionIdFromCookie())
            return (false);

        if (SecurityUtil.isPackageProtectionEnabled())
        {
            return ((Boolean)
                    AccessController.doPrivileged(new PrivilegedAction()
                    {

                        public Object run()
                        {
                            return new Boolean(doIsEncodeable(hreq, session, location));
                        }
                    })).booleanValue();
        } else
        {
            return doIsEncodeable(hreq, session, location);
        }
    }

    private boolean doIsEncodeable(Request hreq, Session session,
                                   String location)
    {
        // Is this a valid absolute URL?
        URL url = null;
        try
        {
            url = new URL(location);
        }
        catch (MalformedURLException e)
        {
            return (false);
        }

        // Does this URL match down to (and including) the context path?
        if (!hreq.getScheme().equalsIgnoreCase(url.getProtocol()))
            return (false);
        if (!hreq.getServerName().equalsIgnoreCase(url.getHost()))
            return (false);
        int serverPort = hreq.getServerPort();
        if (serverPort == -1)
        {
            if ("https".equals(hreq.getScheme()))
                serverPort = 443;
            else
                serverPort = 80;
        }
        int urlPort = url.getPort();
        if (urlPort == -1)
        {
            if ("https".equals(url.getProtocol()))
                urlPort = 443;
            else
                urlPort = 80;
        }
        if (serverPort != urlPort)
            return (false);

        String contextPath = getContext().getPath();
        if (contextPath != null)
        {
            String file = url.getFile();
            if ((file == null) || !file.startsWith(contextPath))
                return (false);
            String tok = ";" + Globals.SESSION_PARAMETER_NAME + "=" + session.getIdInternal();
            if (file.indexOf(tok, contextPath.length()) >= 0)
                return (false);
        }

        // This URL belongs to our web application, so it is encodeable
        return (true);

    }


    /**
     * Convert (if necessary) and return the absolute URL that represents the
     * resource referenced by this possibly relative URL.  If this URL is
     * already absolute, return it unchanged.
     *
     * @param location URL to be (possibly) converted and then returned
     * @throws IllegalArgumentException if a MalformedURLException is
     *                                  thrown when converting the relative URL to an absolute one
     */
    private String toAbsolute(String location)
    {

        if (location == null)
            return (location);

        boolean leadingSlash = location.startsWith("/");

        if (leadingSlash || !hasScheme(location))
        {

            redirectURLCC.recycle();

            String scheme = request.getScheme();
            String name = request.getServerName();
            int port = request.getServerPort();

            try
            {
                redirectURLCC.append(scheme, 0, scheme.length());
                redirectURLCC.append("://", 0, 3);
                redirectURLCC.append(name, 0, name.length());
                if ((scheme.equals("http") && port != 80)
                        || (scheme.equals("https") && port != 443))
                {
                    redirectURLCC.append(':');
                    String portS = port + "";
                    redirectURLCC.append(portS, 0, portS.length());
                }
                if (!leadingSlash)
                {
                    String relativePath = request.getDecodedRequestURI();
                    int pos = relativePath.lastIndexOf('/');
                    relativePath = relativePath.substring(0, pos);

                    String encodedURI = null;
                    final String frelativePath = relativePath;
                    if (SecurityUtil.isPackageProtectionEnabled())
                    {
                        try
                        {
                            encodedURI = (String) AccessController.doPrivileged(
                                    new PrivilegedExceptionAction()
                                    {
                                        public Object run() throws IOException
                                        {
                                            return urlEncoder.encodeURL(frelativePath);
                                        }
                                    });
                        }
                        catch (PrivilegedActionException pae)
                        {
                            IllegalArgumentException iae =
                                    new IllegalArgumentException(location);
                            iae.initCause(pae.getException());
                            throw iae;
                        }
                    } else
                    {
                        encodedURI = urlEncoder.encodeURL(relativePath);
                    }
                    redirectURLCC.append(encodedURI, 0, encodedURI.length());
                    redirectURLCC.append('/');
                }
                redirectURLCC.append(location, 0, location.length());
            }
            catch (IOException e)
            {
                IllegalArgumentException iae =
                        new IllegalArgumentException(location);
                iae.initCause(e);
                throw iae;
            }

            return redirectURLCC.toString();

        } else
        {

            return (location);

        }

    }


    /**
     * Determine if a URI string has a <code>scheme</code> component.
     */
    private boolean hasScheme(String uri)
    {
        int len = uri.length();
        for (int i = 0; i < len; i++)
        {
            char c = uri.charAt(i);
            if (c == ':')
            {
                return i > 0;
            } else if (!URL.isSchemeChar(c))
            {
                return false;
            }
        }
        return false;
    }

    /**
     * Return the specified URL with the specified session identifier
     * suitably encoded.
     *
     * @param url       URL to be encoded with the session id
     * @param sessionId Session id to be included in the encoded URL
     */
    protected String toEncoded(String url, String sessionId)
    {

        if ((url == null) || (sessionId == null))
            return (url);

        String path = url;
        String query = "";
        String anchor = "";
        int question = url.indexOf('?');
        if (question >= 0)
        {
            path = url.substring(0, question);
            query = url.substring(question);
        }
        int pound = path.indexOf('#');
        if (pound >= 0)
        {
            anchor = path.substring(pound);
            path = path.substring(0, pound);
        }
        StringBuffer sb = new StringBuffer(path);
        if (sb.length() > 0)
        { // jsessionid can't be first.
            sb.append(";");
            sb.append(Globals.SESSION_PARAMETER_NAME);
            sb.append("=");
            sb.append(sessionId);
        }
        sb.append(anchor);
        sb.append(query);
        return (sb.toString());

    }


}

