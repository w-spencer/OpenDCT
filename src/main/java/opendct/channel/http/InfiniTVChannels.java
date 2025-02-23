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

package opendct.channel.http;

import opendct.channel.ChannelLineup;
import opendct.channel.TVChannel;
import opendct.channel.TVChannelImpl;
import opendct.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class InfiniTVChannels {
    private static final Logger logger = LogManager.getLogger(InfiniTVChannels.class);

    private static final ReentrantReadWriteLock channelMapLock = new ReentrantReadWriteLock();
    /*private static final HashMap<String, TVChannel> dctChannelMap = new HashMap<String, TVChannel>();
    private static final HashMap<String, TVChannel> qamChannelMap = new HashMap<String, TVChannel>();*/

    // This indicates to remove all SD channels that clearly have an HD equivalent. The associated
    // searching method needs work and probably a lot of special cases will be found.
    /*private static final boolean smartSDFilter =
            Config.getBoolean("http.infinitv.remove_sd_for_hd", false);

    // This removes all channels that do not indicated they are HD.
    private static final boolean removeAllSD =
            Config.getBoolean("http.infinitv.remove_all_sd", false);

    private static final String[] hdLabels =
            Config.getStringArray("http.infinitv.hd_labels_csv", "HD", "DT", "DT2", "DT3");*/
    private static final String[] ignoreNamesContaining =
            Config.getStringArray("channels.infinitv.ignore_names_containing_csv", "Target Ads", "VZ_URL_SOURCE", "VZ_EPG_SOURCE");
    private static final String[] ignoreChannelNumbers =
            Config.getStringArray("channels.infinitv.ignore_channels_csv", "");
    private static final boolean removeDuplicateChannels =
            Config.getBoolean("channels.infinitv.remove_duplicate_channels", true);

    private static final String REQUEST_START =
            "<table style=\"text-align:center;width:600px\"><tr><th>Channel</th><th>Name</th><th>" +
                    "Modulation</th><th>Frequency (kHz)</th><th>Program</th><th>EIA</th></tr>";
    private static final String REQUEST_END = "</table>";

    // Default is every 8 hours. That should be frequent enough.
    private static long updateInterval = 28800000;

    // This will also kick off an update interval thread unless it is disabled in properties.
    public static boolean populateChannels(ChannelLineup channelLineup) {
        logger.entry();

        // Lock immediately.
        channelMapLock.writeLock().lock();

        boolean returnValue = true;

        HttpURLConnection httpURLConnection = null;
        HashSet<String> newChannelList = new HashSet<String>();

        try {
            // If a valid address was provided in the configuration, it should resolve to an IP
            // address. This makes sure that actually happened.
            InetAddress ipAddress = channelLineup.getAddressIP();
            if (ipAddress == null) {
                return logger.exit(false);
            }


            URL url = new URL("http://" + ipAddress.getHostAddress() + ":80/view_channel_map.cgi?page=0");
            logger.info("Connecting to InfiniTV DCT using the URL '{}'", url);

            httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setRequestMethod("GET");
            httpURLConnection.connect();
            InputStreamReader inputStreamReader = new InputStreamReader(httpURLConnection.getInputStream());
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

            boolean parsing = false;
            boolean ignore = false;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                logger.trace("Parsing line '{}'.", line);
                if (parsing) {
                    // This should ensure we are probably working with the right data and
                    // allows us to make some assumptions later.
                    if (line.startsWith("<tr><td>") && line.contains("</td><td>") && line.endsWith("</td></tr>")) {
                        String trim = line.substring("<tr><td>".length(), line.length() - "</th></tr>".length());
                        String values[] = trim.split("</td><td>");

                        if (values.length == 6) {
                            // Check if the name is on the ignore list.
                            ignore = false;
                            for (String ignoreName : ignoreNamesContaining) {
                                if (values[1].contains(ignoreName)) {
                                    logger.debug("Skipping channel {} ({}) because it contains '{}'", values[0], values[1], ignoreName);
                                    ignore = true;
                                    break;
                                }
                            }

                            for (String ignoreChannel : ignoreChannelNumbers) {
                                if (values[0].equals(ignoreChannel)) {
                                    logger.debug("Skipping channel {} ({}) because the channel number is '{}'", values[0], values[1], ignoreChannel);
                                    ignore = true;
                                    break;
                                }
                            }

                            try {
                                int channel = Integer.parseInt(values[0]);
                                //name
                                //modulation
                                int frequency = -1;
                                int program = -1;
                                float eia = -1;
                                try {
                                    // If these can't be parsed, we likely can't use them in any way
                                    // other than the VChannel number, but it could prevent us from
                                    // storing a valid channel if the channel is tuned using SDV.

                                    // Add 3 zeros to the end of the frequency to make it compliant
                                    // with the HDHomeRun.
                                    frequency = Integer.parseInt(values[3]) * 1000;
                                    program = Integer.parseInt(values[4]);
                                    eia = Float.parseFloat(values[5]);
                                } catch (Exception e) {
                                    logger.debug("Unable to parse the " +
                                                    "frequency '{}', program '{}' or eia '{}'. " +
                                                    "This may not be an actual problem, the " +
                                                    "channel will still be added. => {}",
                                            values[3], values[4], values[5], e);
                                }

                                newChannelList.add(values[0]);

                                boolean isDuplicate = false;


                                if (removeDuplicateChannels) {
                                    isDuplicate = channelLineup.isDuplicate(values[0], values[1]);

                                    if (isDuplicate) {
                                        try {
                                            channelLineup.removeChannel(values[0]);
                                        } catch (Exception e) {
                                            logger.error("There was a problem removing the duplicate channel => ", e);
                                        }
                                    }
                                }

                                if (!isDuplicate) {
                                    TVChannel oldChannel = channelLineup.getChannel(values[0]);

                                    boolean updated = false;

                                    if (oldChannel == null) {
                                        logger.debug("Adding new channel...");
                                        TVChannelImpl infiniTVChannel = new TVChannelImpl(
                                                values[0],
                                                values[1],
                                                values[2],
                                                String.valueOf(frequency),
                                                String.valueOf(program),
                                                String.valueOf(eia),
                                                ignore);

                                        channelLineup.addChannel(infiniTVChannel);

                                        updated = true;
                                    } else {
                                        if (!oldChannel.getModulation().equals(values[2])) {
                                            oldChannel.setModulation(values[2]);
                                            updated = true;
                                        }

                                        if (frequency > 0 && !oldChannel.getFrequency().equals(String.valueOf(frequency))) {
                                            oldChannel.setFrequency(String.valueOf(frequency));
                                            updated = true;
                                        }

                                        if (program > 0 && !oldChannel.getProgram().equals(String.valueOf(program))) {
                                            oldChannel.setProgram(String.valueOf(program));
                                            updated = true;
                                        }

                                        if (eia > 0 && !oldChannel.getEia().equals(String.valueOf(eia))) {
                                            oldChannel.setEia(String.valueOf(eia));
                                            updated = true;
                                        }

                                        if (oldChannel.isIgnore() != ignore) {
                                            oldChannel.setIgnore(ignore);
                                            updated = true;
                                        }

                                        if (updated) {
                                            logger.debug("Updating channel values...");
                                            channelLineup.updateChannel(oldChannel);
                                        }
                                    }

                                    if (updated) {
                                        logger.info("Updated InfiniTV channel:" +
                                                        " channel = {}, name = {}, modulation = {}," +
                                                        " frequency = {}, program = {}, eia = {}, ignore = {}",
                                                channel, values[1], values[2],
                                                frequency, program, eia, ignore);
                                    }
                                }
                            } catch (Exception e) {
                                logger.error("Unable to parse the line '{}' from InfiniTV => {}",
                                        line, e);
                            }
                        } else {
                            logger.debug("Skipping the line '{}' because it does not split into 6 strings.", line);
                        }
                    } else if (line.contains(REQUEST_END)) {
                        parsing = false;
                    }
                } else {
                    if (line.contains(REQUEST_START)) {
                        parsing = true;
                    }
                }
            }

            channelLineup.cleanChannels(newChannelList);

            /*if (smartSDFilter || removeAllSD) {
                ArrayList<TVChannel> removeChannels = new ArrayList<TVChannel>();
                boolean keepChannel = false;

                Iterator<Map.Entry<String, TVChannel>> channels = dctChannelMap.entrySet().iterator();
                while (channels.hasNext()) {
                    Map.Entry<String, TVChannel> channelPair = channels.next();
                    TVChannel channel = channelPair.getValue();
                    keepChannel = false;
                    for (String hd : hdLabels) {
                        if (channel.getName().contains(hd)) {
                            keepChannel = true;
                            break;
                        }
                    }

                    logger.debug("The channel {} ({}) does not end with a listed HD label.",
                            channel.getChannel(), channel.getName());

                    if (!keepChannel && !removeAllSD) {
                        keepChannel = true;
                        Iterator compChannels = dctChannelMap.entrySet().iterator();
                        while (compChannels.hasNext()) {
                            Map.Entry<String, TVChannel> compChannelPair =
                                    (Map.Entry<String, TVChannel>) compChannels.next();
                            TVChannel compChannel = compChannelPair.getValue();

                            if (compChannel.getName().startsWith(channel.getName())) {
                                for (String hd : hdLabels) {
                                    // Regex maybe?
                                    if (compChannel.getName().contains(
                                            channel.getName() + " " + hd)) {

                                        keepChannel = false;
                                        break;
                                    } else if (compChannel.getName().contains(
                                            channel.getName() + "-" + hd)) {

                                        keepChannel = false;
                                        break;
                                    } else if (compChannel.getName().contains(
                                            channel.getName() + hd)) {

                                        keepChannel = false;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (!keepChannel) {
                        removeChannels.add(channel);
                    }
                }

                for (TVChannel removeChannel : removeChannels) {
                    logger.debug("Removing the channel {} ({}), because does not appear to be HD.",
                            removeChannel.getChannel(), removeChannel.getName());
                    dctChannelMap.remove(removeChannel);
                }
            }*/
        } catch (Exception e) {
            logger.debug("There was an unhandled exception while using" +
                    " 'channelMapLock' in 'populateChannels' => {}", e);

            returnValue = false;
        } finally {
            channelMapLock.writeLock().unlock();

            try {
                if (httpURLConnection != null) {
                    httpURLConnection.disconnect();
                }
            } catch (Exception e) {
            }
        }

        return logger.exit(returnValue);
    }
}
