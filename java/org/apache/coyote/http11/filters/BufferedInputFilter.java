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

package org.apache.coyote.http11.filters;

import org.apache.coyote.InputBuffer;
import org.apache.coyote.Request;
import org.apache.coyote.http11.InputFilter;
import org.apache.tomcat.util.buf.ByteChunk;

import java.io.IOException;

/**
 * Input filter responsible for reading and buffering the request body, so that
 * it does not interfere with client SSL handshake messages.
 */
public class BufferedInputFilter implements InputFilter
{

    // -------------------------------------------------------------- Constants

    private static final String ENCODING_NAME = "buffered";
    private static final ByteChunk ENCODING = new ByteChunk();


    // ----------------------------------------------------- Instance Variables

    static
    {
        ENCODING.setBytes(ENCODING_NAME.getBytes(), 0, ENCODING_NAME.length());
    }

    private ByteChunk buffered = null;
    private ByteChunk tempRead = new ByteChunk(1024);
    private InputBuffer buffer;


    // ----------------------------------------------------- Static Initializer
    private boolean hasRead = false;


    // --------------------------------------------------------- Public Methods

    /**
     * Set the buffering limit. This should be reset every time the buffer is
     * used.
     */
    public void setLimit(int limit)
    {
        if (buffered == null)
        {
            buffered = new ByteChunk(4048);
            buffered.setLimit(limit);
        }
    }


    // ---------------------------------------------------- InputBuffer Methods


    /**
     * Reads the request body and buffers it.
     */
    public void setRequest(Request request)
    {
        // save off the Request body
        try
        {
            while (buffer.doRead(tempRead, request) >= 0)
            {
                buffered.append(tempRead);
                tempRead.recycle();
            }
        }
        catch (IOException iex)
        {
            // Ignore
        }
    }

    /**
     * Fills the given ByteChunk with the buffered request body.
     */
    public int doRead(ByteChunk chunk, Request request) throws IOException
    {
        if (hasRead || buffered.getLength() <= 0)
        {
            return -1;
        } else
        {
            chunk.setBytes(buffered.getBytes(), buffered.getStart(),
                    buffered.getLength());
            hasRead = true;
        }
        return chunk.getLength();
    }

    public void setBuffer(InputBuffer buffer)
    {
        this.buffer = buffer;
    }

    public void recycle()
    {
        if (buffered != null)
        {
            if (buffered.getBuffer().length > 65536)
            {
                buffered = null;
            } else
            {
                buffered.recycle();
            }
        }
        tempRead.recycle();
        hasRead = false;
        buffer = null;
    }

    public ByteChunk getEncodingName()
    {
        return ENCODING;
    }

    public long end() throws IOException
    {
        return 0;
    }

    public int available()
    {
        return buffered.getLength();
    }

}
