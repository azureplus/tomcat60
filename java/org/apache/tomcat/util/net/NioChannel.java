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


package org.apache.tomcat.util.net;

import org.apache.tomcat.util.MutableInteger;
import org.apache.tomcat.util.net.NioEndpoint.Poller;
import org.apache.tomcat.util.net.SecureNioChannel.ApplicationBufferHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * Base class for a SocketChannel wrapper used by the endpoint.
 * This way, logic for a SSL socket channel remains the same as for
 * a non SSL, making sure we don't need to code for any exception cases.
 *
 * @author Filip Hanik
 * @version 1.0
 */
public class NioChannel implements ByteChannel
{

    protected static ByteBuffer emptyBuf = ByteBuffer.allocate(0);

    protected SocketChannel sc = null;

    protected ApplicationBufferHandler bufHandler;

    protected Poller poller;

    protected boolean sendFile = false;

    public NioChannel(SocketChannel channel, ApplicationBufferHandler bufHandler) throws IOException
    {
        this.sc = channel;
        this.bufHandler = bufHandler;
    }

    public void reset() throws IOException
    {
        bufHandler.getReadBuffer().clear();
        bufHandler.getWriteBuffer().clear();
        this.sendFile = false;
    }

    public int getBufferSize()
    {
        if (bufHandler == null) return 0;
        int size = 0;
        size += bufHandler.getReadBuffer() != null ? bufHandler.getReadBuffer().capacity() : 0;
        size += bufHandler.getWriteBuffer() != null ? bufHandler.getWriteBuffer().capacity() : 0;
        return size;
    }

    /**
     * returns true if the network buffer has
     * been flushed out and is empty
     *
     * @return boolean
     */
    public boolean flush(boolean block, Selector s, long timeout, MutableInteger lastWrite) throws IOException
    {
        if (lastWrite != null) lastWrite.set(1);
        return true; //no network buffer in the regular channel
    }


    /**
     * Closes this channel.
     *
     * @throws IOException If an I/O error occurs
     */
    public void close() throws IOException
    {
        getIOChannel().socket().close();
        getIOChannel().close();
    }

    public void close(boolean force) throws IOException
    {
        if (isOpen() || force) close();
    }

    /**
     * Tells whether or not this channel is open.
     *
     * @return <tt>true</tt> if, and only if, this channel is open
     */
    public boolean isOpen()
    {
        return sc.isOpen();
    }

    /**
     * Writes a sequence of bytes to this channel from the given buffer.
     *
     * @param src The buffer from which bytes are to be retrieved
     * @return The number of bytes written, possibly zero
     * @throws IOException If some other I/O error occurs
     */
    public int write(ByteBuffer src) throws IOException
    {
        return sc.write(src);
    }

    /**
     * Reads a sequence of bytes from this channel into the given buffer.
     *
     * @param dst The buffer into which bytes are to be transferred
     * @return The number of bytes read, possibly zero, or <tt>-1</tt> if the channel has reached end-of-stream
     * @throws IOException If some other I/O error occurs
     */
    public int read(ByteBuffer dst) throws IOException
    {
        return sc.read(dst);
    }

    public Object getAttachment(boolean remove)
    {
        Poller pol = getPoller();
        Selector sel = pol != null ? pol.getSelector() : null;
        SelectionKey key = sel != null ? getIOChannel().keyFor(sel) : null;
        Object att = key != null ? key.attachment() : null;
        if (key != null && att != null && remove) key.attach(null);
        return att;
    }

    public ApplicationBufferHandler getBufHandler()
    {
        return bufHandler;
    }

    public Poller getPoller()
    {
        return poller;
    }

    public void setPoller(Poller poller)
    {
        this.poller = poller;
    }

    public SocketChannel getIOChannel()
    {
        return sc;
    }

    public void setIOChannel(SocketChannel IOChannel)
    {
        this.sc = IOChannel;
    }

    public boolean isClosing()
    {
        return false;
    }

    public boolean isInitHandshakeComplete()
    {
        return true;
    }

    public int handshake(boolean read, boolean write) throws IOException
    {
        return 0;
    }

    public String toString()
    {
        return super.toString() + ":" + this.sc.toString();
    }

    public int getOutboundRemaining()
    {
        return 0;
    }

    /**
     * Return true if the buffer wrote data.
     *
     * @throws IOException
     */
    public boolean flushOutbound() throws IOException
    {
        return false;
    }

    public boolean isSendFile()
    {
        return sendFile;
    }

    public void setSendFile(boolean s)
    {
        this.sendFile = s;
    }
}
