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

package opendct.sagetv;

import opendct.config.Config;
import opendct.config.ExitCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SageTVDiscovery implements Runnable {
    private static final Logger logger = LogManager.getLogger(SageTVDiscovery.class);
    private static AtomicBoolean running = new AtomicBoolean(false);
    private static int broadcastPort = Config.getInteger("sagetv.encoder_discovery_port", 8271);
    private boolean useLoopback = Config.getBoolean("sagetv.use_automatic_loopback", true);
    private static Thread sageTVDiscoveryThread = new Thread(new SageTVDiscovery());
    private static int encoderPort = 0;

    public static boolean isRunning() {
        return running.get();
    }

    /**
     * Starts the SageTV encoder discovery broadcast.
     */
    public static void startDiscoveryBroadcast(int encoderPort) {
        logger.entry();

        // We are only building the configuration file.
        if (Config.isConfigOnly()) {
            logger.exit();
            return;
        }

        if (running.getAndSet(true)) {
            logger.warn("Discovery broadcast is already running.");
            return;
        }

        SageTVDiscovery.encoderPort = encoderPort;
        sageTVDiscoveryThread.setName("SageTVDiscovery-" + sageTVDiscoveryThread.getId());
        sageTVDiscoveryThread.start();

        logger.exit();
    }

    /**
     * Stops the SageTV encoder discovery broadcast and blocks until the thread is stopped.
     */
    public static void stopDiscoveryBroadcast() {
        logger.entry();

        // We are only building the configuration file.
        if (Config.isConfigOnly()) {
            logger.exit();
            return;
        }

        try {
            sageTVDiscoveryThread.interrupt();
            sageTVDiscoveryThread.join();
        } catch (InterruptedException e) {
            logger.debug("Discovery broadcast was interrupted while waiting for the thread to stop => {}", e);
        }

        logger.exit();
    }

    public void run() {
        logger.entry();

        DatagramChannel datagramChannel = null;
        ByteBuffer datagramPacket = ByteBuffer.allocate(4096);

        try {
            datagramChannel = DatagramChannel.open();
            datagramChannel.socket().bind(new InetSocketAddress(broadcastPort));

            SageTVManager.blockUntilCaptureDevicesLoaded();

            HashMap<String, Long> lastResponse = new HashMap<String, Long>();

            while (!Thread.currentThread().isInterrupted()) {

                datagramPacket.clear();
                InetSocketAddress datagramSocketAddress = (InetSocketAddress)datagramChannel.receive(datagramPacket);
                datagramPacket.flip();

                logger.debug("Received discovery datagram from SageTV server '{}' and validating...", datagramSocketAddress.toString());

                String lastServer = datagramSocketAddress.getAddress().toString();
                Long lastServerResponse = lastResponse.get(lastServer);

                if (lastServerResponse != null && lastServerResponse > System.currentTimeMillis()) {
                    lastServerResponse = System.currentTimeMillis() + 11000;
                    lastResponse.put(lastServer, lastServerResponse);

                    logger.warn("Duplicate discovery request. Ignoring.");
                    continue;
                } else {
                    lastServerResponse = System.currentTimeMillis() + 11000;
                    lastResponse.put(lastServer, lastServerResponse);
                }

                if (datagramPacket.get() != (char) 'S') continue;
                if (datagramPacket.get() != (char) 'T') continue;
                if (datagramPacket.get() != (char) 'N') continue;
                if (datagramPacket.get() < (byte) 4) continue;
                if (datagramPacket.get() < (byte) 1) continue;
                if (datagramPacket.get() < (byte) 0) continue;

                logger.debug("Validated discovery datagram from SageTV server '{}' and preparing response...", datagramSocketAddress.toString());

                datagramPacket.clear();
                datagramPacket.put("STN".getBytes(Config.STD_BYTE));
                datagramPacket.put((byte) 4);
                datagramPacket.put((byte) 1);
                datagramPacket.put((byte) 0);

                datagramPacket.put((byte) ((SageTVDiscovery.encoderPort >> 8) & 0xff));
                datagramPacket.put((byte) (SageTVDiscovery.encoderPort & 0xff));

                byte hostnameBytes[];
                if (useLoopback && datagramSocketAddress.toString().contains(datagramChannel.socket().getLocalAddress().getHostAddress())) {
                    hostnameBytes = "127.0.0.1".getBytes(Config.STD_BYTE);
                } else {
                    hostnameBytes = datagramChannel.socket().getLocalAddress().getHostAddress().getBytes(Config.STD_BYTE);
                }

                // If we using the actual hostname we can't use the IP address
                // later and we will get duplicate devices.
                //byte hostnameBytes[] = hostname.getBytes("UTF-8");

                datagramPacket.put((byte) hostnameBytes.length);
                datagramPacket.put(hostnameBytes);

                datagramPacket.flip();

                datagramChannel.send(datagramPacket, datagramSocketAddress);
                logger.info("Sent discovery response datagram to SageTV server '{}'.", datagramSocketAddress.toString());
            }
        } catch (IOException e) {
            logger.error("Unable to open the network encoder discovery broadcast port => {}", e);
            ExitCode.SAGETV_DISCOVERY.terminateJVM();
        } catch (Exception e) {
            logger.error("An unexpected exception happened in the network encoder discovery thread => {}", e);
        } finally {
            if (datagramChannel != null) {
                try {
                    datagramChannel.close();
                } catch (IOException e) {
                    logger.warn("An IO Exception occurred while closing the discovery broadcast port => {}", e);
                }
            }

            running.set(false);
        }

        logger.exit();
    }
}
