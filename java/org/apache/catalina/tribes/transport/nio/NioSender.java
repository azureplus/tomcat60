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

package org.apache.catalina.tribes.transport.nio;

import org.apache.catalina.tribes.RemoteProcessException;
import org.apache.catalina.tribes.io.XByteBuffer;
import org.apache.catalina.tribes.transport.AbstractSender;
import org.apache.catalina.tribes.transport.DataSender;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

/**
 * This class is NOT thread safe and should never be used with more than one thread at a time
 * <p/>
 * This is a state machine, handled by the process method
 * States are:
 * - NOT_CONNECTED -> connect() -> CONNECTED
 * - CONNECTED -> setMessage() -> READY TO WRITE
 * - READY_TO_WRITE -> write() -> READY TO WRITE | READY TO READ
 * - READY_TO_READ -> read() -> READY_TO_READ | TRANSFER_COMPLETE
 * - TRANSFER_COMPLETE -> CONNECTED
 *
 * @author Filip Hanik
 * @version 1.0
 */
public class NioSender extends AbstractSender implements DataSender
{

    protected static org.apache.juli.logging.Log log = org.apache.juli.logging.LogFactory.getLog(NioSender.class);


    protected Selector selector;
    protected SocketChannel socketChannel;

    /*
     * STATE VARIABLES *
     */
    protected ByteBuffer readbuf = null;
    protected ByteBuffer writebuf = null;
    protected byte[] current = null;
    protected XByteBuffer ackbuf = new XByteBuffer(128, true);
    protected int remaining = 0;
    protected boolean complete;

    protected boolean connecting = false;

    public NioSender()
    {
        super();

    }

    /**
     * State machine to send data
     *
     * @param key SelectionKey
     * @return boolean
     * @throws IOException
     */
    public boolean process(SelectionKey key, boolean waitForAck) throws IOException
    {
        int ops = key.readyOps();
        key.interestOps(key.interestOps() & ~ops);
        //in case disconnect has been called
        if ((!isConnected()) && (!connecting))
            throw new IOException("Sender has been disconnected, can't selection key.");
        if (!key.isValid()) throw new IOException("Key is not valid, it must have been cancelled.");
        if (key.isConnectable())
        {
            if (socketChannel.finishConnect())
            {
                completeConnect();
                if (current != null) key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                return false;
            } else
            {
                //wait for the connection to finish
                key.interestOps(key.interestOps() | SelectionKey.OP_CONNECT);
                return false;
            }//end if
        } else if (key.isWritable())
        {
            boolean writecomplete = write(key);
            if (writecomplete)
            {
                //we are completed, should we read an ack?
                if (waitForAck)
                {
                    //register to read the ack
                    key.interestOps(key.interestOps() | SelectionKey.OP_READ);
                } else
                {
                    //if not, we are ready, setMessage will reregister us for another write interest
                    //do a health check, we have no way of verify a disconnected
                    //socket since we don't register for OP_READ on waitForAck=false
                    read(key);//this causes overhead
                    setRequestCount(getRequestCount() + 1);
                    return true;
                }
            } else
            {
                //we are not complete, lets write some more
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }//end if
        } else if (key.isReadable())
        {
            boolean readcomplete = read(key);
            if (readcomplete)
            {
                setRequestCount(getRequestCount() + 1);
                return true;
            } else
            {
                key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            }//end if
        } else
        {
            //unknown state, should never happen
            log.warn("Data is in unknown state. readyOps=" + ops);
            throw new IOException("Data is in unknown state. readyOps=" + ops);
        }//end if
        return false;
    }

    private void completeConnect() throws SocketException
    {
        //we connected, register ourselves for writing
        setConnected(true);
        connecting = false;
        setRequestCount(0);
        setConnectTime(System.currentTimeMillis());
        socketChannel.socket().setSendBufferSize(getTxBufSize());
        socketChannel.socket().setReceiveBufferSize(getRxBufSize());
        socketChannel.socket().setSoTimeout((int) getTimeout());
        socketChannel.socket().setSoLinger(getSoLingerOn(), getSoLingerOn() ? getSoLingerTime() : 0);
        socketChannel.socket().setTcpNoDelay(getTcpNoDelay());
        socketChannel.socket().setKeepAlive(getSoKeepAlive());
        socketChannel.socket().setReuseAddress(getSoReuseAddress());
        socketChannel.socket().setOOBInline(getOoBInline());
        socketChannel.socket().setSoLinger(getSoLingerOn(), getSoLingerTime());
        socketChannel.socket().setTrafficClass(getSoTrafficClass());
    }


    protected boolean read(SelectionKey key) throws IOException
    {
        //if there is no message here, we are done
        if (current == null) return true;
        int read = socketChannel.read(readbuf);
        //end of stream
        if (read == -1)
            throw new IOException("Unable to receive an ack message. EOF on socket channel has been reached.");
            //no data read
        else if (read == 0) return false;
        readbuf.flip();
        ackbuf.append(readbuf, read);
        readbuf.clear();
        if (ackbuf.doesPackageExist())
        {
            byte[] ackcmd = ackbuf.extractDataPackage(true).getBytes();
            boolean ack = Arrays.equals(ackcmd, org.apache.catalina.tribes.transport.Constants.ACK_DATA);
            boolean fack = Arrays.equals(ackcmd, org.apache.catalina.tribes.transport.Constants.FAIL_ACK_DATA);
            if (fack && getThrowOnFailedAck())
                throw new RemoteProcessException("Received a failed ack:org.apache.catalina.tribes.transport.Constants.FAIL_ACK_DATA");
            return ack || fack;
        } else
        {
            return false;
        }
    }


    protected boolean write(SelectionKey key) throws IOException
    {
        if ((!isConnected()) || (this.socketChannel == null))
        {
            throw new IOException("NioSender is not connected, this should not occur.");
        }
        if (current != null)
        {
            if (remaining > 0)
            {
                //weve written everything, or we are starting a new package
                //protect against buffer overwrite
                int byteswritten = socketChannel.write(writebuf);
                if (byteswritten == -1) throw new EOFException();
                remaining -= byteswritten;
                //if the entire message was written from the buffer
                //reset the position counter
                if (remaining < 0)
                {
                    remaining = 0;
                }
            }
            return (remaining == 0);
        }
        //no message to send, we can consider that complete
        return true;
    }

    /**
     * connect - blocking in this operation
     */
    public synchronized void connect() throws IOException
    {
        if (connecting) return;
        connecting = true;
        if (isConnected()) throw new IOException("NioSender is already in connected state.");
        if (readbuf == null)
        {
            readbuf = getReadBuffer();
        } else
        {
            readbuf.clear();
        }
        if (writebuf == null)
        {
            writebuf = getWriteBuffer();
        } else
        {
            writebuf.clear();
        }

        InetSocketAddress addr = new InetSocketAddress(getAddress(), getPort());
        if (socketChannel != null)
            throw new IOException("Socket channel has already been established. Connection might be in progress.");
        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        if (socketChannel.connect(addr))
        {
            completeConnect();
            socketChannel.register(getSelector(), SelectionKey.OP_WRITE, this);
        } else
        {
            socketChannel.register(getSelector(), SelectionKey.OP_CONNECT, this);
        }
    }


    public void disconnect()
    {
        try
        {
            connecting = false;
            setConnected(false);
            if (socketChannel != null)
            {
                try
                {
                    try
                    {
                        socketChannel.socket().close();
                    }
                    catch (Exception x)
                    {
                    }
                    //error free close, all the way
                    //try {socket.shutdownOutput();}catch ( Exception x){}
                    //try {socket.shutdownInput();}catch ( Exception x){}
                    //try {socket.close();}catch ( Exception x){}
                    try
                    {
                        socketChannel.close();
                    }
                    catch (Exception x)
                    {
                    }
                }
                finally
                {
                    socketChannel = null;
                }
            }
        }
        catch (Exception x)
        {
            log.error("Unable to disconnect NioSender. msg=" + x.getMessage());
            if (log.isDebugEnabled()) log.debug("Unable to disconnect NioSender. msg=" + x.getMessage(), x);
        }
        finally
        {
        }

    }

    public void reset()
    {
        if (isConnected() && readbuf == null)
        {
            readbuf = getReadBuffer();
        }
        if (readbuf != null) readbuf.clear();
        if (writebuf != null) writebuf.clear();
        current = null;
        ackbuf.clear();
        remaining = 0;
        complete = false;
        setAttempt(0);
        setRequestCount(0);
        setConnectTime(-1);
    }

    private ByteBuffer getReadBuffer()
    {
        return getBuffer(getRxBufSize());
    }

    private ByteBuffer getWriteBuffer()
    {
        return getBuffer(getTxBufSize());
    }

    private ByteBuffer getBuffer(int size)
    {
        return (getDirectBuffer() ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size));
    }

    public synchronized void setMessage(byte[] data, int offset, int length) throws IOException
    {
        if (data != null)
        {
            current = data;
            remaining = length;
            ackbuf.clear();
            if (writebuf != null) writebuf.clear();
            else writebuf = getBuffer(length);
            if (writebuf.capacity() < length) writebuf = getBuffer(length);
            writebuf.put(data, offset, length);
            //writebuf.rewind();
            //set the limit so that we don't write non wanted data
            //writebuf.limit(length);
            writebuf.flip();
            if (isConnected())
            {
                socketChannel.register(getSelector(), SelectionKey.OP_WRITE, this);
            }
        }
    }

    public byte[] getMessage()
    {
        return current;
    }

    public synchronized void setMessage(byte[] data) throws IOException
    {
        setMessage(data, 0, data.length);
    }

    public boolean isComplete()
    {
        return complete;
    }

    public void setComplete(boolean complete)
    {
        this.complete = complete;
    }

    public Selector getSelector()
    {
        return selector;
    }

    public void setSelector(Selector selector)
    {
        this.selector = selector;
    }
}
