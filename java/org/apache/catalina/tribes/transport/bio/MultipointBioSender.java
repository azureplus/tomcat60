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

package org.apache.catalina.tribes.transport.bio;

import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelException;
import org.apache.catalina.tribes.ChannelMessage;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.io.ChannelData;
import org.apache.catalina.tribes.io.XByteBuffer;
import org.apache.catalina.tribes.transport.AbstractSender;
import org.apache.catalina.tribes.transport.MultiPointSender;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Filip Hanik
 */
public class MultipointBioSender extends AbstractSender implements MultiPointSender
{
    protected long selectTimeout = 1000;
    protected HashMap bioSenders = new HashMap();
    public MultipointBioSender()
    {
    }

    public synchronized void sendMessage(Member[] destination, ChannelMessage msg) throws ChannelException
    {
        byte[] data = XByteBuffer.createDataPackage((ChannelData) msg);
        BioSender[] senders = setupForSend(destination);
        ChannelException cx = null;
        for (int i = 0; i < senders.length; i++)
        {
            try
            {
                senders[i].sendMessage(data, (msg.getOptions() & Channel.SEND_OPTIONS_USE_ACK) == Channel.SEND_OPTIONS_USE_ACK);
            }
            catch (Exception x)
            {
                if (cx == null) cx = new ChannelException(x);
                cx.addFaultyMember(destination[i], x);
            }
        }
        if (cx != null) throw cx;
    }


    protected BioSender[] setupForSend(Member[] destination) throws ChannelException
    {
        ChannelException cx = null;
        BioSender[] result = new BioSender[destination.length];
        for (int i = 0; i < destination.length; i++)
        {
            try
            {
                BioSender sender = (BioSender) bioSenders.get(destination[i]);
                if (sender == null)
                {
                    sender = new BioSender();
                    sender.transferProperties(this, sender);
                    sender.setDestination(destination[i]);
                    bioSenders.put(destination[i], sender);
                }
                result[i] = sender;
                if (!result[i].isConnected()) result[i].connect();
                result[i].keepalive();
            }
            catch (Exception x)
            {
                if (cx == null) cx = new ChannelException(x);
                cx.addFaultyMember(destination[i], x);
            }
        }
        if (cx != null) throw cx;
        else return result;
    }

    public void connect() throws IOException
    {
        //do nothing, we connect on demand
        setConnected(true);
    }


    private synchronized void close() throws ChannelException
    {
        ChannelException x = null;
        Object[] members = bioSenders.keySet().toArray();
        for (int i = 0; i < members.length; i++)
        {
            Member mbr = (Member) members[i];
            try
            {
                BioSender sender = (BioSender) bioSenders.get(mbr);
                sender.disconnect();
            }
            catch (Exception e)
            {
                if (x == null) x = new ChannelException(e);
                x.addFaultyMember(mbr, e);
            }
            bioSenders.remove(mbr);
        }
        if (x != null) throw x;
    }

    public void add(Member member)
    {

    }

    public void remove(Member member)
    {
        //disconnect senders
        BioSender sender = (BioSender) bioSenders.remove(member);
        if (sender != null) sender.disconnect();
    }


    public synchronized void disconnect()
    {
        try
        {
            close();
        }
        catch (Exception x)
        {
        }
        setConnected(false);
    }

    public void finalize()
    {
        try
        {
            disconnect();
        }
        catch (Exception ignore)
        {
        }
    }


    public boolean keepalive()
    {
        //throw new UnsupportedOperationException("Method ParallelBioSender.checkKeepAlive() not implemented");
        boolean result = false;
        Map.Entry[] entries = (Map.Entry[]) bioSenders.entrySet().toArray(new Map.Entry[bioSenders.size()]);
        for (int i = 0; i < entries.length; i++)
        {
            BioSender sender = (BioSender) entries[i].getValue();
            if (sender.keepalive())
            {
                bioSenders.remove(entries[i].getKey());
            }
        }
        return result;
    }

}