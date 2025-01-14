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

package org.apache.coyote.memory;

import org.apache.coyote.*;
import org.apache.tomcat.util.buf.ByteChunk;

import java.io.IOException;
import java.util.Iterator;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 */
public class MemoryProtocolHandler
        implements ProtocolHandler
{


    // ------------------------------------------------------------- Properties


    /**
     * Associated adapter.
     */
    protected Adapter adapter = null;

    /**
     * Pass config info.
     */
    public void setAttribute(String name, Object value)
    {
    }

    public Object getAttribute(String name)
    {
        return null;
    }

    public Iterator getAttributeNames()
    {
        return null;
    }

    public Adapter getAdapter()
    {
        return (adapter);
    }

    /**
     * The adapter, used to call the connector.
     */
    public void setAdapter(Adapter adapter)
    {
        this.adapter = adapter;
    }


    // ------------------------------------------------ ProtocolHandler Methods

    /**
     * Init the protocol.
     */
    public void init()
            throws Exception
    {
    }


    /**
     * Start the protocol.
     */
    public void start()
            throws Exception
    {
    }


    public void pause()
            throws Exception
    {
    }

    public void resume()
            throws Exception
    {
    }

    public void destroy()
            throws Exception
    {
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Process specified request.
     */
    public void process(Request request, ByteChunk input,
                        Response response, ByteChunk output)
            throws Exception
    {

        InputBuffer inputBuffer = new ByteChunkInputBuffer(input);
        OutputBuffer outputBuffer = new ByteChunkOutputBuffer(output);
        request.setInputBuffer(inputBuffer);
        response.setOutputBuffer(outputBuffer);

        adapter.service(request, response);

    }


    // --------------------------------------------- ByteChunkInputBuffer Class


    protected class ByteChunkInputBuffer
            implements InputBuffer
    {

        protected ByteChunk input = null;

        public ByteChunkInputBuffer(ByteChunk input)
        {
            this.input = input;
        }

        public int doRead(ByteChunk chunk, Request request)
                throws IOException
        {
            return input.substract(chunk);
        }

    }


    // -------------------------------------------- ByteChunkOuptutBuffer Class


    protected class ByteChunkOutputBuffer
            implements OutputBuffer
    {

        protected ByteChunk output = null;

        public ByteChunkOutputBuffer(ByteChunk output)
        {
            this.output = output;
        }

        public int doWrite(ByteChunk chunk, Response response)
                throws IOException
        {
            output.append(chunk);
            return chunk.getLength();
        }

    }


}
