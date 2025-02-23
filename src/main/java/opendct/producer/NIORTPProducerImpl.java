/*
 * Copyright 2015 The OpenDCT Authors. All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package opendct.producer;

import opendct.config.Config;
import opendct.consumer.SageTVConsumer;
import opendct.video.rtsp.DCTRTSPClientImpl;
import opendct.video.rtsp.RTSPClient;
import opendct.video.rtsp.rtp.RTPPacketProcessor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class NIORTPProducerImpl implements RTPProducer {
    private final Logger logger = LogManager.getLogger(NIORTPProducerImpl.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private RTPPacketProcessor packetProcessor = new RTPPacketProcessor();

    private volatile long packetsReceived = 0;
    private volatile long packetsLastReceived = 0;
    private int localPort = 0;
    private final int udpReceiveBufferSize =
            Config.getInteger("producer.nio.udp_receive_buffer", 1328000);
    private InetAddress remoteIPAddress = null;
    private DatagramChannel datagramChannel = null;
    private Thread timeoutThread = null;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final Object receiveMonitor = new Object();

    private SageTVConsumer sageTVConsumer = null;

    public synchronized void setStreamingSocket(InetAddress streamRemoteIP, int streamLocalPort) throws IOException {
        logger.entry(streamRemoteIP, streamLocalPort);
        if (running.get()) {
            throw new IOException("The IP address and port for RTP producer cannot be changed while the thread is running.");
        }

        this.localPort = streamLocalPort;
        this.remoteIPAddress = streamRemoteIP;

        try {
            datagramChannel = DatagramChannel.open();
            datagramChannel.socket().bind(new InetSocketAddress(this.localPort));
            datagramChannel.socket().setBroadcast(false);
            datagramChannel.socket().setReceiveBufferSize(udpReceiveBufferSize);

            // In case 0 was used and a port was automatically chosen.
            this.localPort = datagramChannel.socket().getLocalPort();
        } catch (IOException e) {
            if (datagramChannel != null && datagramChannel.isConnected()) {
                try {
                    datagramChannel.close();
                    datagramChannel.socket().close();
                } catch (IOException e0) {
                    logger.debug("Producer created an exception while closing the datagram channel => {}", e0);
                }
            }
            throw e;
        }

        logger.exit();
    }

    public boolean getIsRunning() {
        return running.get();
    }

    public synchronized void setConsumer(SageTVConsumer sageTVConsumer) throws IOException {
        if (running.get()) {
            throw new IOException("The consumer cannot be changed while the thread is running.");
        }

        this.sageTVConsumer = sageTVConsumer;
    }

    public int getPacketsLost() {
        return packetProcessor.getMissedRTPPackets();
    }

    public void stopProducing() {
        if (stop.getAndSet(true)) {
            return;
        }

        if (timeoutThread != null) {
            timeoutThread.interrupt();
        }

        datagramChannel.socket().close();
    }

    public int getLocalPort() {
        return localPort;
    }

    public InetAddress getRemoteIPAddress() {
        return remoteIPAddress;
    }

    public void run() throws IllegalThreadStateException {
        if (running.getAndSet(true)) {
            logger.warn("The producer is already running.");
            throw new IllegalThreadStateException("The producer is already running.");
        }

        if (stop.getAndSet(false)) {
            logger.warn("Producer was requesting to stop before it started.");
            throw new IllegalThreadStateException("The producer is still stopping.");
        }

        logger.info("Producer thread is running.");

        timeoutThread = new Thread(new Runnable() {
            @Override
            public void run() {

                logger.info("Producer packet monitoring thread is running.");
                boolean firstPacketsReceived = true;

                while(!stop.get() && !Thread.currentThread().isInterrupted()) {
                    synchronized (receiveMonitor) {
                        packetsLastReceived = packetsReceived;
                    }

                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        logger.debug("The packet monitoring thread has been interrupted.");
                        break;
                    }

                    long recentPackets;

                    synchronized (receiveMonitor) {
                        recentPackets = packetsReceived;
                        packetsReceived = 0;
                    }

                    if (recentPackets == packetsLastReceived) {
                        logger.info("No packets received in over 5 seconds.");

                        if (datagramChannel != null) {
                            synchronized (receiveMonitor) {
                                try {
                                    datagramChannel.close();
                                    // The datagram channel doesn't seem to close the socket every time.
                                    datagramChannel.socket().close();
                                } catch (IOException e) {
                                    logger.debug("Producer created an exception while closing the datagram channel => ", e);
                                }

                                datagramChannel = null;
                            }
                        }

                        try {
                            DatagramChannel newChannel = DatagramChannel.open();
                            newChannel.socket().bind(new InetSocketAddress(localPort));
                            newChannel.socket().setBroadcast(false);
                            newChannel.socket().setReceiveBufferSize(udpReceiveBufferSize);

                            synchronized (receiveMonitor) {
                                datagramChannel = newChannel;
                                receiveMonitor.notifyAll();
                            }
                        } catch (SocketException e) {
                            logger.error("Producer created an exception while configuring a socket => ", e);
                        } catch (IOException e) {
                            logger.error("Producer created an exception while opening a new datagram channel => ", e);
                        }
                    }

                    if (recentPackets > 0) {
                        if (firstPacketsReceived) {
                            firstPacketsReceived = false;
                            logger.info("Received first {} datagram packets.", recentPackets);
                        }
                    }
                }

                logger.info("Producer packet monitoring thread has stopped.");
            }
        });

        timeoutThread.setName("PacketsMonitor-" + timeoutThread.getId() + ":" + Thread.currentThread().getName());
        timeoutThread.start();

        // We could be doing channel scanning that doesn't need this kind of prioritization.
        if (Thread.currentThread().getPriority() != Thread.MIN_PRIORITY) {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        }

        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
            synchronized (receiveMonitor) {
                while(datagramChannel == null) {
                    try {
                        receiveMonitor.wait(1000);
                    } catch (InterruptedException e) {
                        logger.info("Producer was interrupted while waiting for a new datagram channel => ", e);
                        break;
                    }

                    if (stop.get()) {
                        break;
                    }
                }
            }

            if (stop.get()) {
                break;
            }

            try {
                int datagramSize = -1;

                // A standard RTP transmitted datagram payload should not be larger than 1328 bytes.
                ByteBuffer datagramBuffer = ByteBuffer.allocate(1500);

                while (!Thread.currentThread().isInterrupted()) {
                    datagramBuffer.clear();

                    logger.trace("Waiting for datagram...");
                    datagramChannel.receive(datagramBuffer);
                    datagramSize = datagramBuffer.position();
                    datagramBuffer.flip();

                    //Copying and queuing bad packets wastes resources.
                    if (datagramSize > 12) {
                        // Keeps a counter updated with how many RTP packets we probably
                        // lost and in the case of a byte buffers, it moves the index
                        // position to 12.
                        packetProcessor.findMissingRTPPackets(datagramBuffer);

                        sageTVConsumer.write(datagramBuffer.array(), datagramBuffer.position(), datagramBuffer.remaining());
                    }

                    synchronized (receiveMonitor) {
                        packetsReceived += 1;
                    }
                }
            } catch (ClosedByInterruptException e) {
                logger.debug("Producer was closed by an interrupt exception => ", e);
            } catch (AsynchronousCloseException e) {
                logger.debug("Producer was closed by an asynchronous close exception => ", e);
            } catch (ClosedChannelException e) {
                logger.debug("Producer was closed by a close channel exception => ", e);
            } catch (Exception e) {
                logger.error("Producer created an unexpected exception => ", e);
            } finally {
                logger.info("Producer thread has disconnected.");
            }
        }

        if (datagramChannel != null) {
            try {
                datagramChannel.close();
                // The datagram channel doesn't seem to close the socket every time.
                datagramChannel.socket().close();
            } catch (IOException e) {
                logger.debug("Producer created an exception while closing the datagram channel => ", e);
            }
        }

        logger.info("Producer thread has stopped.");
        running.set(false);
        stop.set(false);
    }

    public long getPackets() {
        synchronized (receiveMonitor) {
            return packetsReceived;
        }
    }
}