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

import org.apache.coyote.ActionCode;
import org.apache.coyote.OutputBuffer;
import org.apache.coyote.Response;
import org.apache.coyote.http11.filters.GzipOutputFilter;
import org.apache.tomcat.util.MutableInteger;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.http.HttpMessages;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioSelectorPool;
import org.apache.tomcat.util.res.StringManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * Output buffer.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 * @author Filip Hanik
 */
public class InternalNioOutputBuffer
        implements OutputBuffer
{


    // -------------------------------------------------------------- Constants


    /**
     * Logger.
     */
    private static final org.apache.juli.logging.Log log
            = org.apache.juli.logging.LogFactory.getLog(
            InternalNioOutputBuffer.class);
    /**
     * The string manager for this package.
     */
    protected static StringManager sm =
            StringManager.getManager(Constants.Package);
    /**
     * Associated Coyote response.
     */
    protected Response response;


    // -------------------------------------------------------------- Variables
    /**
     * Headers of the associated request.
     */
    protected MimeHeaders headers;
    /**
     * Committed flag.
     */
    protected boolean committed;

    // ----------------------------------------------------- Instance Variables
    /**
     * Finished flag.
     */
    protected boolean finished;
    /**
     * Pointer to the current write buffer.
     */
    protected byte[] buf;
    /**
     * Position in the buffer.
     */
    protected int pos;
    /**
     * Number of bytes last written
     */
    protected MutableInteger lastWrite = new MutableInteger(1);
    /**
     * Underlying socket.
     */
    protected NioChannel socket;
    /**
     * Selector pool, for blocking reads and blocking writes
     */
    protected NioSelectorPool pool;
    /**
     * Underlying output buffer.
     */
    protected OutputBuffer outputStreamOutputBuffer;
    /**
     * Filter library.
     * Note: Filter[0] is always the "chunked" filter.
     */
    protected OutputFilter[] filterLibrary;
    /**
     * Active filter (which is actually the top of the pipeline).
     */
    protected OutputFilter[] activeFilters;
    /**
     * Index of the last active filter.
     */
    protected int lastActiveFilter;
    // ----------------------------------------------------------- Constructors
    int bbufLimit = 0;
    int total = 0;


    /**
     * Default constructor.
     */
    public InternalNioOutputBuffer(Response response)
    {
        this(response, Constants.DEFAULT_HTTP_HEADER_BUFFER_SIZE);
    }

    // ------------------------------------------------------------- Properties


    /**
     * Alternate constructor.
     */
    public InternalNioOutputBuffer(Response response, int headerBufferSize)
    {

        this.response = response;
        headers = response.getMimeHeaders();

        buf = new byte[headerBufferSize];

        if (headerBufferSize < (8 * 1024))
        {
            bbufLimit = 6 * 1500;
        } else
        {
            bbufLimit = (headerBufferSize / 1500 + 1) * 1500;
        }
        //bbuf = ByteBuffer.allocateDirect(bbufLimit);

        outputStreamOutputBuffer = new SocketOutputBuffer();

        filterLibrary = new OutputFilter[0];
        activeFilters = new OutputFilter[0];
        lastActiveFilter = -1;

        committed = false;
        finished = false;

        // Cause loading of HttpMessages
        HttpMessages.getMessage(200);

    }

    /**
     * Get the underlying socket input stream.
     */
    public NioChannel getSocket()
    {
        return socket;
    }

    /**
     * Set the underlying socket.
     */
    public void setSocket(NioChannel socket)
    {
        this.socket = socket;
    }

    public NioSelectorPool getSelectorPool()
    {
        return pool;
    }

    public void setSelectorPool(NioSelectorPool pool)
    {
        this.pool = pool;
    }

    /**
     * Set the socket buffer size.
     */
    public void setSocketBuffer(int socketBufferSize)
    {
        // FIXME: Remove
    }

    /**
     * Add an output filter to the filter library.
     */
    public void addFilter(OutputFilter filter)
    {

        OutputFilter[] newFilterLibrary =
                new OutputFilter[filterLibrary.length + 1];
        for (int i = 0; i < filterLibrary.length; i++)
        {
            newFilterLibrary[i] = filterLibrary[i];
        }
        newFilterLibrary[filterLibrary.length] = filter;
        filterLibrary = newFilterLibrary;

        activeFilters = new OutputFilter[filterLibrary.length];

    }

    /**
     * Get filters.
     */
    public OutputFilter[] getFilters()
    {

        return filterLibrary;

    }

    /**
     * Clear filters.
     */
    public void clearFilters()
    {

        filterLibrary = new OutputFilter[0];
        lastActiveFilter = -1;

    }


    // --------------------------------------------------------- Public Methods

    /**
     * Add an output filter to the filter library.
     */
    public void addActiveFilter(OutputFilter filter)
    {

        if (lastActiveFilter == -1)
        {
            filter.setBuffer(outputStreamOutputBuffer);
        } else
        {
            for (int i = 0; i <= lastActiveFilter; i++)
            {
                if (activeFilters[i] == filter)
                    return;
            }
            filter.setBuffer(activeFilters[lastActiveFilter]);
        }

        activeFilters[++lastActiveFilter] = filter;

        filter.setResponse(response);

    }

    /**
     * Flush the response.
     *
     * @throws IOException an undelying I/O error occured
     */
    public void flush()
            throws IOException
    {

        if (!committed)
        {

            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeader) and 
            // set the filters accordingly.
            response.action(ActionCode.ACTION_COMMIT, null);

        }

        // go through the filters and if there is gzip filter
        // invoke it to flush
        for (int i = 0; i <= lastActiveFilter; i++)
        {
            if (activeFilters[i] instanceof GzipOutputFilter)
            {
                if (log.isDebugEnabled())
                {
                    log.debug("Flushing the gzip filter at position " + i +
                            " of the filter chain...");
                }
                ((GzipOutputFilter) activeFilters[i]).flush();
                break;
            }
        }

        // Flush the current buffer
        flushBuffer();

    }

    /**
     * Reset current response.
     *
     * @throws IllegalStateException if the response has already been committed
     */
    public void reset()
    {

        if (committed)
            throw new IllegalStateException(/*FIXME:Put an error message*/);

        // Recycle Request object
        response.recycle();
    }

    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    public void recycle()
    {
        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++)
        {
            activeFilters[i].recycle();
        }

        // Recycle Request object
        response.recycle();
        if (socket != null)
        {
            socket.getBufHandler().getWriteBuffer().clear();
            socket = null;
        }
        pos = 0;
        lastActiveFilter = -1;
        committed = false;
        finished = false;
        lastWrite.set(1);

    }

    /**
     * End processing of current HTTP request.
     * Note: All bytes of the current request should have been already
     * consumed. This method only resets all the pointers so that we are ready
     * to parse the next HTTP request.
     */
    public void nextRequest()
    {

        // Recycle Request object
        response.recycle();

        // Recycle filters
        for (int i = 0; i <= lastActiveFilter; i++)
        {
            activeFilters[i].recycle();
        }

        // Reset pointers
        pos = 0;
        lastActiveFilter = -1;
        committed = false;
        finished = false;

    }

    /**
     * End request.
     *
     * @throws IOException an undelying I/O error occured
     */
    public void endRequest()
            throws IOException
    {

        if (!committed)
        {

            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeader) and 
            // set the filters accordingly.
            response.action(ActionCode.ACTION_COMMIT, null);

        }

        if (finished)
            return;

        if (lastActiveFilter != -1)
            activeFilters[lastActiveFilter].end();

        flushBuffer();

        finished = true;

    }
    // ------------------------------------------------ HTTP/1.1 Output Methods

    public boolean isWritable()
    {
        return lastWrite.get() > 0;
    }

    /**
     * Send an acknoledgement.
     */
    public void sendAck()
            throws IOException
    {

        if (!committed)
        {
            //Socket.send(socket, Constants.ACK_BYTES, 0, Constants.ACK_BYTES.length) < 0
            socket.getBufHandler().getWriteBuffer().put(Constants.ACK_BYTES, 0, Constants.ACK_BYTES.length);
            writeToSocket(socket.getBufHandler().getWriteBuffer(), true, true);
        }

    }

    /**
     * @param bytebuffer ByteBuffer
     * @param flip       boolean
     * @return int
     * @throws IOException
     * @todo Fix non blocking write properly
     */
    private synchronized int writeToSocket(ByteBuffer bytebuffer, boolean block, boolean flip) throws IOException
    {
        if (flip) bytebuffer.flip();

        int written = 0;
        NioEndpoint.KeyAttachment att = (NioEndpoint.KeyAttachment) socket.getAttachment(false);
        if (att == null) throw new IOException("Key must be cancelled");
        long writeTimeout = att.getTimeout();
        Selector selector = null;
        try
        {
            selector = getSelectorPool().get();
        }
        catch (IOException x)
        {
            //ignore
        }
        try
        {
            written = getSelectorPool().write(bytebuffer, socket, selector, writeTimeout, block, lastWrite);
            //make sure we are flushed 
            do
            {
                if (socket.flush(true, selector, writeTimeout, lastWrite)) break;
            } while (true);
        }
        finally
        {
            if (selector != null) getSelectorPool().put(selector);
        }
        if (block) bytebuffer.clear(); //only clear
        this.total = 0;
        return written;
    }

    /**
     * Send the response status line.
     */
    public void sendStatus()
    {

        // Write protocol name
        write(Constants.HTTP_11_BYTES);
        buf[pos++] = Constants.SP;

        // Write status code
        int status = response.getStatus();
        switch (status)
        {
            case 200:
                write(Constants._200_BYTES);
                break;
            case 400:
                write(Constants._400_BYTES);
                break;
            case 404:
                write(Constants._404_BYTES);
                break;
            default:
                write(status);
        }

        buf[pos++] = Constants.SP;

        // Write message
        String message = null;
        if (org.apache.coyote.Constants.USE_CUSTOM_STATUS_MSG_IN_HEADER &&
                HttpMessages.isSafeInHttpHeader(response.getMessage()))
        {
            message = response.getMessage();
        }
        if (message == null)
        {
            write(HttpMessages.getMessage(status));
        } else
        {
            write(message);
        }

        // End the response status line
        buf[pos++] = Constants.CR;
        buf[pos++] = Constants.LF;

    }

    /**
     * Send a header.
     *
     * @param name  Header name
     * @param value Header value
     */
    public void sendHeader(MessageBytes name, MessageBytes value)
    {

        write(name);
        buf[pos++] = Constants.COLON;
        buf[pos++] = Constants.SP;
        write(value);
        buf[pos++] = Constants.CR;
        buf[pos++] = Constants.LF;

    }

    /**
     * Send a header.
     *
     * @param name  Header name
     * @param value Header value
     */
    public void sendHeader(ByteChunk name, ByteChunk value)
    {

        write(name);
        buf[pos++] = Constants.COLON;
        buf[pos++] = Constants.SP;
        write(value);
        buf[pos++] = Constants.CR;
        buf[pos++] = Constants.LF;

    }

    /**
     * Send a header.
     *
     * @param name  Header name
     * @param value Header value
     */
    public void sendHeader(String name, String value)
    {

        write(name);
        buf[pos++] = Constants.COLON;
        buf[pos++] = Constants.SP;
        write(value);
        buf[pos++] = Constants.CR;
        buf[pos++] = Constants.LF;

    }


    // --------------------------------------------------- OutputBuffer Methods

    /**
     * End the header block.
     */
    public void endHeaders()
    {

        buf[pos++] = Constants.CR;
        buf[pos++] = Constants.LF;

    }


    // ------------------------------------------------------ Protected Methods

    /**
     * Write the contents of a byte chunk.
     *
     * @param chunk byte chunk
     * @return number of bytes written
     * @throws IOException an undelying I/O error occured
     */
    public int doWrite(ByteChunk chunk, Response res)
            throws IOException
    {

        if (!committed)
        {

            // Send the connector a request for commit. The connector should
            // then validate the headers, send them (using sendHeaders) and 
            // set the filters accordingly.
            response.action(ActionCode.ACTION_COMMIT, null);

        }

        if (lastActiveFilter == -1)
            return outputStreamOutputBuffer.doWrite(chunk, res);
        else
            return activeFilters[lastActiveFilter].doWrite(chunk, res);

    }

    /**
     * Commit the response.
     *
     * @throws IOException an undelying I/O error occured
     */
    protected void commit()
            throws IOException
    {

        // The response is now committed
        committed = true;
        response.setCommitted(true);

        if (pos > 0)
        {
            // Sending the response header buffer
            addToBB(buf, 0, pos);
        }

    }

    private synchronized void addToBB(byte[] buf, int offset, int length) throws IOException
    {
        while (length > 0)
        {
            int thisTime = length;
            if (socket.getBufHandler().getWriteBuffer().position() ==
                    socket.getBufHandler().getWriteBuffer().capacity()
                    || socket.getBufHandler().getWriteBuffer().remaining() == 0)
            {
                flushBuffer();
            }
            if (thisTime > socket.getBufHandler().getWriteBuffer().remaining())
            {
                thisTime = socket.getBufHandler().getWriteBuffer().remaining();
            }
            socket.getBufHandler().getWriteBuffer().put(buf, offset, thisTime);
            length = length - thisTime;
            offset = offset + thisTime;
            total += thisTime;
        }
        NioEndpoint.KeyAttachment ka = (NioEndpoint.KeyAttachment) socket.getAttachment(false);
        if (ka != null) ka.access();//prevent timeouts for just doing client writes
    }


    /**
     * This method will write the contents of the specyfied message bytes
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param mb data to be written
     */
    protected void write(MessageBytes mb)
    {

        if (mb.getType() == MessageBytes.T_BYTES)
        {
            ByteChunk bc = mb.getByteChunk();
            write(bc);
        } else if (mb.getType() == MessageBytes.T_CHARS)
        {
            CharChunk cc = mb.getCharChunk();
            write(cc);
        } else
        {
            write(mb.toString());
        }

    }


    /**
     * This method will write the contents of the specyfied message bytes
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param bc data to be written
     */
    protected void write(ByteChunk bc)
    {

        // Writing the byte chunk to the output buffer
        int length = bc.getLength();
        System.arraycopy(bc.getBytes(), bc.getStart(), buf, pos, length);
        pos = pos + length;

    }


    /**
     * This method will write the contents of the specyfied char
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param cc data to be written
     */
    protected void write(CharChunk cc)
    {

        int start = cc.getStart();
        int end = cc.getEnd();
        char[] cbuf = cc.getBuffer();
        for (int i = start; i < end; i++)
        {
            char c = cbuf[i];
            // Note:  This is clearly incorrect for many strings,
            // but is the only consistent approach within the current
            // servlet framework.  It must suffice until servlet output
            // streams properly encode their output.
            if (((c <= 31) && (c != 9)) || c == 127 || c > 255)
            {
                c = ' ';
            }
            buf[pos++] = (byte) c;
        }

    }


    /**
     * This method will write the contents of the specyfied byte
     * buffer to the output stream, without filtering. This method is meant to
     * be used to write the response header.
     *
     * @param b data to be written
     */
    public void write(byte[] b)
    {

        // Writing the byte chunk to the output buffer
        System.arraycopy(b, 0, buf, pos, b.length);
        pos = pos + b.length;

    }


    /**
     * This method will write the contents of the specyfied String to the
     * output stream, without filtering. This method is meant to be used to
     * write the response header.
     *
     * @param s data to be written
     */
    protected void write(String s)
    {

        if (s == null)
            return;

        // From the Tomcat 3.3 HTTP/1.0 connector
        int len = s.length();
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(i);
            // Note:  This is clearly incorrect for many strings,
            // but is the only consistent approach within the current
            // servlet framework.  It must suffice until servlet output
            // streams properly encode their output.
            if (((c <= 31) && (c != 9)) || c == 127 || c > 255)
            {
                c = ' ';
            }
            buf[pos++] = (byte) c;
        }

    }


    /**
     * This method will print the specified integer to the output stream,
     * without filtering. This method is meant to be used to write the
     * response header.
     *
     * @param i data to be written
     */
    protected void write(int i)
    {

        write(String.valueOf(i));

    }


    /**
     * Callback to write data from the buffer.
     */
    protected void flushBuffer()
            throws IOException
    {

        //prevent timeout for async,
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key != null)
        {
            NioEndpoint.KeyAttachment attach = (NioEndpoint.KeyAttachment) key.attachment();
            attach.access();
        }

        //write to the socket, if there is anything to write
        if (socket.getBufHandler().getWriteBuffer().position() > 0)
        {
            socket.getBufHandler().getWriteBuffer().flip();
            writeToSocket(socket.getBufHandler().getWriteBuffer(), true, false);
        }
    }


    // ----------------------------------- OutputStreamOutputBuffer Inner Class


    /**
     * This class is an output buffer which will write data to an output
     * stream.
     */
    protected class SocketOutputBuffer
            implements OutputBuffer
    {


        /**
         * Write chunk.
         */
        public int doWrite(ByteChunk chunk, Response res)
                throws IOException
        {

            int len = chunk.getLength();
            int start = chunk.getStart();
            byte[] b = chunk.getBuffer();
            addToBB(b, start, len);
            return chunk.getLength();

        }


    }


}
