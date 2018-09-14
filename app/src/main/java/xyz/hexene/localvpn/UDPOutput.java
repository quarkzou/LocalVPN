/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package xyz.hexene.localvpn;

import android.content.pm.PackageInfo;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private LocalVPNService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private Selector selector;

    private static final int MAX_CACHE_SIZE = 50;
    private LRUCache<String, DatagramChannel> channelCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, DatagramChannel>()
            {
                @Override
                public void cleanup(Map.Entry<String, DatagramChannel> eldest)
                {
                    closeChannel(eldest.getValue());
                }
            });

    public UDPOutput(ConcurrentLinkedQueue<Packet> inputQueue, Selector selector, LocalVPNService vpnService)
    {
        this.inputQueue = inputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
    }

    private class PackageInfo
    {
        public Packet Pack;
        public long InsertTick;

        public PackageInfo(Packet pack, long insertTick)
        {
            Pack = pack;
            InsertTick = insertTick;
        }

    }

    private Queue<PackageInfo> m_outputQueue = new ConcurrentLinkedQueue<PackageInfo>();

    private boolean m_drop = false;
    private int m_dropNum = 30;

    @Override
    public void run()
    {
        Log.i(TAG, "Started");
        try
        {
            int packIndex = 0;

            Thread currentThread = Thread.currentThread();
            while (true)
            {
                Packet currentPacket;
                // TODO: Block when not connected
                do
                {
                    currentPacket = inputQueue.poll();
                    if (currentPacket != null)
                    {
                        m_outputQueue.add(new PackageInfo(currentPacket, System.currentTimeMillis()));
                    }

                    PackageInfo pi = m_outputQueue.peek();
                    if(pi != null)
                    {
                        long interval = System.currentTimeMillis() - pi.InsertTick;
                        if(interval > 50) {
                            currentPacket = m_outputQueue.poll().Pack;
                            ++packIndex;
                            if(packIndex == m_dropNum) {
                                packIndex = 0;
                                m_drop = !m_drop;
                            }
                            break;
                        }
                    }

                    Thread.sleep(1);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;

                if(m_drop)
                    continue;

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                DatagramChannel outputChannel = channelCache.get(ipAndPort);
                if (outputChannel == null) {
                    outputChannel = DatagramChannel.open();
                    vpnService.protect(outputChannel.socket());
                    try
                    {
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                    }
                    catch (IOException e)
                    {
                        Log.e(TAG, "Connection error: " + ipAndPort, e);
                        closeChannel(outputChannel);
                        ByteBufferPool.release(currentPacket.backingBuffer);
                        continue;
                    }
                    outputChannel.configureBlocking(false);
                    currentPacket.swapSourceAndDestination();

                    selector.wakeup();
                    outputChannel.register(selector, SelectionKey.OP_READ, currentPacket);

                    channelCache.put(ipAndPort, outputChannel);
                }

                try
                {
                    ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                    Log.i(TAG, String.format("ZYDEBUG, vpn=>remote, size=%d, ip&port=%s", payloadBuffer.limit() - payloadBuffer.position(), ipAndPort));

                    while (payloadBuffer.hasRemaining())
                        outputChannel.write(payloadBuffer);
                }
                catch (IOException e)
                {
                    Log.e(TAG, "Network write error: " + ipAndPort, e);
                    channelCache.remove(ipAndPort);
                    closeChannel(outputChannel);
                }
                ByteBufferPool.release(currentPacket.backingBuffer);
            }
        }
        catch (InterruptedException e)
        {
            Log.i(TAG, "Stopping");
        }
        catch (IOException e)
        {
            Log.i(TAG, e.toString(), e);
        }
        finally
        {
            closeAll();
        }
    }

    private void closeAll()
    {
        Iterator<Map.Entry<String, DatagramChannel>> it = channelCache.entrySet().iterator();
        while (it.hasNext())
        {
            closeChannel(it.next().getValue());
            it.remove();
        }
    }

    private void closeChannel(DatagramChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}
