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

package opendct.channel;

import opendct.channel.http.InfiniTVChannels;
import opendct.channel.http.PrimeChannels;
import opendct.config.Config;
import opendct.config.ConfigBag;
import opendct.power.PowerEventListener;
import opendct.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChannelManager implements PowerEventListener {
    private static final Logger logger = LogManager.getLogger(ChannelManager.class);

    public static PowerEventListener POWER_EVENT_LISTENER = new ChannelManager();
    private AtomicBoolean suspend = new AtomicBoolean(false);

    // This is used to try to ensure that we don't create duplicates that are still scanning
    // channels and provides one place when we are stopping the program to make sure all channel
    // scans stop.
    final private static ConcurrentHashMap<String, OfflineChannelScan> offlineScansMap =
            new ConcurrentHashMap<String, OfflineChannelScan>();

    // This is used to map capture devices to their respective offline channel scans. This is how a
    // capture device opts in to offline scanning. If there are no capture devices associated with a
    // lineup, offline scanning will not happen on this lineup.
    final private static ConcurrentHashMap<String, HashSet<String>> offlineScanDevicesMap =
            new ConcurrentHashMap<String, HashSet<String>>();

    // This is used to map channel lineup names to their respective channel lineup objects.
    final private static ConcurrentHashMap<String, ChannelLineup> channelLineupsMap =
            new ConcurrentHashMap<String, ChannelLineup>();

    private static AtomicBoolean updateRunning = new AtomicBoolean(false);
    private static Thread updateThread;
    private static boolean noOfflineScan = false;


    /**
     * Returns the offline channel scan object for the provided name.
     *
     * @param scanName This if the name of the offline channel scan.
     * @return The offline channel scan or null if a corresponding object is not found.
     */
    public static OfflineChannelScan getOfflineChannelScan(String scanName) {
        return offlineScansMap.get(scanName);
    }

    /**
     * Adds a capture device to the offline scan for a a lineup.
     * <p/>
     * If the capture device was already added, it is overwritten.
     *
     * @param lineupName        This is the name of the lineup.
     * @param captureDeviceName This is the friendly name of the capture device.
     */
    public synchronized static void addDeviceToOfflineScan(String lineupName, String captureDeviceName) {
        HashSet<String> devices = offlineScanDevicesMap.get(lineupName);

        if (devices == null) {
            devices = new HashSet<String>();
            offlineScanDevicesMap.put(lineupName, devices);
        }

        devices.add(captureDeviceName);
    }

    /**
     * Removes a capture device to the offline scan for a a lineup.
     *
     * @param lineupName        This is the name of the lineup.
     * @param captureDeviceName This is the friendly name of the capture device.
     */
    public synchronized static void removeDeviceFromOfflineScan(String lineupName, String captureDeviceName) {
        HashSet<String> devices = offlineScanDevicesMap.get(lineupName);

        if (devices == null) {
            return;
        }

        devices.remove(captureDeviceName);
    }

    /**
     * Starts an offline channel scan based on the scanning schedule and the currently submitted
     * capture devices.
     * <p/>
     * A channel scan will start if it is scheduled. It will also start if a scan has never run or
     * the last scan failed. If the scan fails to start, the scheduled time will not be incremented
     * and it will be tried again the next time this method is called.
     *
     * @param now <i>true</i> to ignore the schedule and run all channel updates right now.
     */
    public synchronized static void startAllOfflineChannelScans(boolean now) {
        for (Map.Entry<String, HashSet<String>> offlineScan : offlineScanDevicesMap.entrySet()) {
            String scanName = offlineScan.getKey();
            ChannelLineup channelLineup = channelLineupsMap.get(scanName);

            final OfflineChannelScan offlineChannelScan = offlineScansMap.get(scanName);

            if (offlineChannelScan != null &&
                    (offlineChannelScan.isRunning() || offlineChannelScan.isComplete())) {

                ArrayList<TVChannel> channels = offlineChannelScan.getScannedChannelsAndClear();

                if (offlineChannelScan.isComplete()) {

                    // Get any remaining channels if they exist.
                    channels.addAll(offlineChannelScan.getScannedChannelsAndClear());

                    for (TVChannel channel : channels) {
                        channelLineup.updateChannel(channel);
                    }

                    // The scan is now complete. Save the results immediately.
                    saveChannelLineup(scanName);

                    // Remove the scan from the map so we don't keep pulling channels from it.
                    offlineScansMap.remove(scanName);
                } else {
                    for (TVChannel channel : channels) {
                        channelLineup.updateChannel(channel);
                    }
                }
            }

            if (offlineChannelScan != null && !offlineChannelScan.isRunning() && !offlineChannelScan.isComplete()) {
                // If the scan is no longer running and the scan did not successfully complete, try
                // again.
                if (!startOfflineChannelScan(scanName)) {
                    continue;
                }
            } else if (!now && channelLineup.getNextOfflineUpdate() > System.currentTimeMillis()) {
                // Skip if it is not time to run a new update.
                continue;
            } else {
                // We are scheduled to start.
                if (!startOfflineChannelScan(scanName)) {
                    continue;
                }
            }

            channelLineup.setNextOfflineUpdate(System.currentTimeMillis() + channelLineup.getUpdateInterval());
        }
    }

    /**
     * Starts an offline channel scan based on the currently submitted capture devices.
     * <p/>
     * If there are no capture devices registered or a scan is already in progress, the scan will
     * not start. This method also blocks until all required devices are loaded. Never call this
     * method while initializing a capture device.
     *
     * @param lineupName This is the name of the lineup.
     * @return <i>true</i> if a scan was started.
     */
    public synchronized static boolean startOfflineChannelScan(String lineupName) {

        String devices[] = getDevicesForOfflineScan(lineupName);
        OfflineChannelScan channelScan = getOfflineChannelScan(lineupName);

        if (devices == null || devices.length == 0) {
            return false;
        } else if (channelScan != null && channelScan.isRunning()) {
            return false;
        }

        channelScan = new OfflineChannelScan(lineupName, devices);
        offlineScansMap.put(lineupName, channelScan);

        // We don't want to waste resources seeing if we can tune into a channel that we are
        // ignoring.
        return channelScan.start(getChannelList(lineupName, false, true), 2000);
    }

    /**
     * Gets all currently registered devices for offline scanning for the requested lineup.
     *
     * @param lineupName This is the name of the lineup.
     * @return An array of the names of all registered capture devices to be used for offline
     * scanning of this lineup.
     */
    public synchronized static String[] getDevicesForOfflineScan(String lineupName) {
        HashSet<String> devices = offlineScanDevicesMap.get(lineupName);

        if (devices == null) {
            return new String[0];
        }

        String returnValues[] = new String[devices.size()];
        int i = 0;

        for (String device : devices) {
            returnValues[i++] = device;
        }

        return returnValues;
    }

    /**
     * Add a channel scan to the offline channel scan map.
     * <p/>
     * Adding the channel scan to this map allows it to be managed from a central location and
     * the channel manager will handle suspend events for you. This method should only be used
     * in situations whereby an offline channel scan is not just processing a lineup.
     *
     * @param offlineChannelScan Offline scan to add to map.
     */
    protected static void addOfflineChannelScan(OfflineChannelScan offlineChannelScan) {
        offlineScansMap.put(offlineChannelScan.SCAN_NAME, offlineChannelScan);
    }

    /**
     * Stops any offline channel scans if they are in progress and only returns when they are
     * stopped.
     */
    public static void stopAllOfflineScansAndWait() {

        // Stop all of the scans.
        for (Map.Entry<String, OfflineChannelScan> offlineScan : offlineScansMap.entrySet()) {
            offlineScan.getValue().stop();
        }

        // Wait for all of the scans to stop.
        for (Map.Entry<String, OfflineChannelScan> offlineScan : offlineScansMap.entrySet()) {
            try {
                offlineScan.getValue().blockUntilComplete();
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Returns if the requested lineup has any channels.
     *
     * @param lineupName This is the name of the lineup.
     * @return <i>true</i> if the lineup exists and contains channels.
     */
    public static boolean hasChannels(String lineupName) {
        ChannelLineup channelLineup = channelLineupsMap.get(lineupName);

        if (channelLineup == null) {
            return false;
        }

        return channelLineup.hasChannels();
    }

    /**
     * Returns a channel from a lineup.
     * <p/>
     * If the channel cannot be found, a <i>null</i> value will be returned. If a channel redirect
     * is in effect for the requested channel you will be redirected.
     *
     * @param lineupName This is the name of the lineup.
     * @param channel    This is the channel to look up.
     * @return Returns a channel or <i>null</i> if the channel does not exist in the lineup.
     */
    public static TVChannel getChannel(String lineupName, String channel) {
        final ChannelLineup channelLineup = channelLineupsMap.get(lineupName);

        if (channelLineup == null) {
            return null;
        }

        return channelLineup.getChannel(channel);
    }

    /**
     * Add a channel to a lineup.
     *
     * @param lineupName This is the name of the lineup.
     * @param channel    This is the channel to add.
     * @return <i>true</i> if the lineup was found and the channel was added.
     */
    public static boolean addChannel(String lineupName, TVChannel channel) {
        final ChannelLineup channelLineup = channelLineupsMap.get(lineupName);

        if (channelLineup == null) {
            return false;
        }

        channelLineup.addChannel(channel);
        return true;
    }

    /**
     * Update a channel on a lineup.
     *
     * @param lineupName This is the name of the lineup.
     * @param channel    This is the channel to update.
     * @return <i>true</i> if the lineup was found and the channel was updated.
     */
    public static boolean updateChannel(String lineupName, TVChannel channel) {
        final ChannelLineup channelLineup = channelLineupsMap.get(lineupName);

        if (channelLineup == null) {
            return false;
        }

        channelLineup.updateChannel(channel);
        return true;
    }

    /**
     * Returns all of the channels available for the requested lineup.
     * <p/>
     * The returned list is a copy of the channels, not the original objects.
     *
     * @param lineupName This is the name of the lineup.
     * @return A list of all channels in the requested lineup.
     */
    public static TVChannel[] getChannelList(String lineupName, boolean includeIgnored, boolean includeNonTunable) {
        ChannelLineup channelLineup = channelLineupsMap.get(lineupName);

        if (channelLineup == null) {
            return new TVChannel[0];
        }

        ArrayList<TVChannel> sourceChannels = channelLineup.getAllChannels(includeIgnored, includeNonTunable);
        TVChannel returnChannels[] = new TVChannel[sourceChannels.size()];

        for (int i = 0; i < returnChannels.length; i++) {
            try {
                returnChannels[i] = new TVChannelImpl(sourceChannels.get(i).getProperties());
            } catch (Exception e) {
                logger.error("Unable to create a new channel => ", e);
            }
        }

        return returnChannels;
    }

    /**
     * Add a new channel line up.
     *
     * @param channelLineup This is the the initialized channel lineup.
     * @param overwrite     If <i>true</i> and a channel lineup with the same name already exists, it
     *                      will be overwritten.
     * @return <i>true</i> if the add was successful. This will only return <i>false</i> if
     * overwrite is set to <i>false</i> and the lineup names conflict.
     */
    public static boolean addChannelLineup(ChannelLineup channelLineup, boolean overwrite) {
        if (overwrite) {
            channelLineupsMap.put(channelLineup.LINEUP_NAME, channelLineup);
            return true;
        }

        ChannelLineup lineupLookup = channelLineupsMap.get(channelLineup.LINEUP_NAME);
        if (lineupLookup != null) {
            return false;
        }

        channelLineupsMap.put(channelLineup.LINEUP_NAME, channelLineup);
        return true;
    }

    /**
     * Removes a channel lineup from the lineups.
     *
     * @param lineupName The name of the lineup to be removed.
     * @param delete     If <i>true</i>, it will also delete the properties file associated with the
     *                   lineup.
     * @return <i>true</i> if the remove was successful. This will only return <i>false</i> if
     * delete is set to <i>true</i> and the file deletion failed.
     */
    public static boolean removeChannelLineup(String lineupName, boolean delete) {
        channelLineupsMap.remove(lineupName);
        offlineScanDevicesMap.remove(lineupName);

        if (delete) {
            String lineupPath = Config.getConfigDirectory() + Config.DIR_SEPARATOR + "lineup" + Config.DIR_SEPARATOR + lineupName + ".properties";
            File lineupFile = new File(lineupPath);
            if (lineupFile.exists()) {
                return lineupFile.delete();
            }
        }

        return true;
    }

    /**
     * Returns all of the channel lineup names.
     *
     * @return Returns an array of the channel lineups.
     */
    public static ArrayList<ChannelLineup> getChannelLineups() {
        ArrayList<ChannelLineup> returnValues = new ArrayList<ChannelLineup>();

        for (Map.Entry<String, ChannelLineup> lineupMapPair : channelLineupsMap.entrySet()) {
            returnValues.add(lineupMapPair.getValue());
        }

        return returnValues;
    }

    /**
     * Get a channel lineup object.
     *
     * @param lineupName The name of the channel lineup.
     * @return The channel lineup object or <i>null</i> if the lineup doesn't exist.
     */
    public static ChannelLineup getChannelLineup(String lineupName) {
        return channelLineupsMap.get(lineupName);
    }

    /**
     * Saves all loaded channel lineups.
     */
    public static void saveChannelLineups() {
        for (Map.Entry<String, ChannelLineup> lineupMapPair : channelLineupsMap.entrySet()) {
            saveChannelLineup(lineupMapPair.getKey());
        }
    }

    /**
     * Updates all loaded channel lineups from their respective sources.
     *
     * @param now <i>true</i> to ignore the schedule and run all channel updates right now.
     */
    public static void updateChannelLineups(boolean now) {
        for (Map.Entry<String, ChannelLineup> lineupPair : channelLineupsMap.entrySet()) {
            final ChannelLineup channelLineup = lineupPair.getValue();

            if (!now && channelLineup.getNextUpdate() > System.currentTimeMillis() && channelLineup.hasChannels()) {
                continue;
            }

            updateChannelLineup(channelLineup);

            channelLineup.setNextUpdate(System.currentTimeMillis() + channelLineup.getUpdateInterval());
        }
    }

    public static void updateChannelLineup(ChannelLineup channelLineup) {
        switch (channelLineup.SOURCE) {
            case INFINITV:
                logger.info("Updating the InfiniTV channel lineup {} ({}).", channelLineup.getFriendlyName(), channelLineup.LINEUP_NAME);
                InfiniTVChannels.populateChannels(channelLineup);
                break;
            case PRIME:
                logger.info("Updating the Prime channel lineup {} ({}).", channelLineup.getFriendlyName(), channelLineup.LINEUP_NAME);
                PrimeChannels.populateChannels(channelLineup);
                break;
            case STATIC:
                logger.info("The static channel lineup {} ({}) will remain unchanged.", channelLineup.getFriendlyName(), channelLineup.LINEUP_NAME);
                break;
        }
    }

    /**
     * Loads all available channel lineups.
     */
    public static void loadChannelLineups() {
        String lineupPath = Config.getConfigDirectory() + Config.DIR_SEPARATOR + "lineup";

        File directory = new File(lineupPath);

        if (Util.createDirectory(lineupPath)) {
            File[] lineups = directory.listFiles(new FileFilter() {
                public boolean accept(File pathname) {
                    return pathname.getName().endsWith(".properties");
                }
            });

            for (File lineup : lineups) {
                String lineupName = lineup.getName().substring(0, lineup.getName().length() - ".properties".length());
                loadChannelLineup(lineupName);
            }
        }
    }

    /**
     * Loads a channel lineup.
     * <p/>
     * If the lineup is already loaded, it will replace channels in the lineup with the value from
     * the file. It will not remove any channels. If the resulting lineup is empty, it will run an
     * update to populate the channels.
     *
     * @param lineupName This is the name of the lineup.
     * @return <i>true</i> if the load was successful.
     */
    public static boolean loadChannelLineup(String lineupName) {
        ConfigBag configBag = new ConfigBag(lineupName, "lineup", false);

        if (configBag.loadConfig()) {
            ChannelLineup lineup = channelLineupsMap.get(lineupName);

            if (lineup == null) {
                String friendlyName = configBag.getString("lineup.friendly_name", lineupName);
                String address = configBag.getString("lineup.address", "");
                long updateInterval = configBag.getLong(
                        "lineup.update_interval",
                        ChannelLineup.DEFAULT_UPDATE_INTERVAL);

                long offlineUpdateInterval = configBag.getLong(
                        "lineup.offline_update_interval",
                        ChannelLineup.DEFAULT_OFFLINE_UPDATE_INTERVAL);

                String sourceString = configBag.getString(
                        "lineup.source",
                        String.valueOf(ChannelSourceType.STATIC));

                ChannelSourceType sourceType = ChannelSourceType.STATIC;

                try {
                    sourceType = ChannelSourceType.valueOf(sourceString);
                } catch (Exception e) {
                    logger.error("The value '{}' is not a valid channel update source," +
                            " using the default '{}'.",
                            sourceString,
                            String.valueOf(ChannelSourceType.STATIC));
                }

                lineup = new ChannelLineup(lineupName, friendlyName, sourceType, address,
                        updateInterval, offlineUpdateInterval);

                channelLineupsMap.put(lineup.LINEUP_NAME, lineup);
            }

            final HashMap<String, String> loadedChannels = configBag.getAllByRootKey("channel.");

            for (Map.Entry<String, String> channelMapPair : loadedChannels.entrySet()) {
                final String properties = channelMapPair.getValue();

                try {
                    lineup.addChannel(new TVChannelImpl(Util.getStringArrayFromCSV(properties)));
                } catch (Exception e) {
                    logger.error("Unable to create a new channel => ", e);
                }
            }

            if (lineup.SOURCE != ChannelSourceType.STATIC && !lineup.hasChannels()) {
                updateChannelLineup(lineup);
            }

            return true;
        }

        return false;
    }

    /**
     * Saves the requested channel lineup to a properties file so it can be restored later.
     *
     * @param lineupName This is the name of the lineup to be saved.
     * @return <i>true</i> if it was successfully saved.
     */
    public static boolean saveChannelLineup(String lineupName) {

        ChannelLineup lineup = channelLineupsMap.get(lineupName);

        if (lineup == null) {
            return false;
        }

        ConfigBag configBag = new ConfigBag(lineupName, "lineup", false);
        configBag.loadConfig();

        // Remove all current channels so we don't retain any old values or channels we have
        // removed.
        configBag.removeAllByRootKey("channel.");

        configBag.setString("lineup.friendly_name", lineup.getFriendlyName());
        configBag.setString("lineup.address", lineup.getAddress());
        configBag.setLong("lineup.update_interval", lineup.getUpdateInterval());
        configBag.setLong("lineup.offline_update_interval", lineup.getOfflineUpdateInterval());
        configBag.setString("lineup.source", String.valueOf(lineup.SOURCE));

        StringBuilder unavailableChannels = new StringBuilder();
        StringBuilder availableChannels = new StringBuilder();

        for (TVChannel tvChannel : lineup.getAllChannels(true, true)) {

            String channel = tvChannel.getChannel();
            while (channel.length() < 4) {
                channel = "0" + channel;
            }

            configBag.setStringArray("channel." + channel, tvChannel.getProperties());

            if (!tvChannel.isTunable() || tvChannel.isIgnore()) {
                unavailableChannels.append(tvChannel.getChannel());
                unavailableChannels.append(",");
            } else if (tvChannel.isTunable() && !tvChannel.isIgnore()) {
                availableChannels.append(tvChannel.getChannel());
                availableChannels.append(",");
            }
        }

        // Remove the last comma.
        if (unavailableChannels.length() > 0) {
            unavailableChannels.deleteCharAt(unavailableChannels.length() - 1);
        }

        // Remove the last comma.
        if (availableChannels.length() > 0) {
            availableChannels.deleteCharAt(availableChannels.length() - 1);
        }

        configBag.removeAllByRootKey("sagetv.unavailable_channels_ref");
        configBag.removeAllByRootKey("sagetv.available_channels_ref");
        configBag.setString("sagetv.unavailable_channels_ref", unavailableChannels.toString());
        configBag.setString("sagetv.available_channels_ref", availableChannels.toString());

        return configBag.saveConfig();
    }

    public static void startUpdateChannelsThread() {
        logger.entry();

        updateThread = new Thread(new Runnable() {
            public void run() {
                if (updateRunning.getAndSet(true)) {
                    logger.debug("The ChannelManager update thread has already started.");
                    return;
                }

                try {
                    // Wait one minute for things to settle before trying to do any scans or
                    // updates. Any updates that need to be done immediately or the capture device
                    // might not work as intended should be done by the capture device itself at
                    // initialization. This thread is only to keep things periodically updated.
                    Thread.sleep(60000);

                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            if (!Thread.currentThread().isInterrupted()) {
                                // No update schedules should be happening this quickly.
                                Thread.sleep(1000);
                                updateChannelLineups(false);

                                // We should not be starting this kind of lengthy process while we
                                // are just configuring things.
                                if (!Config.isConfigOnly()) {
                                    startAllOfflineChannelScans(false);
                                }
                            }
                        } catch (InterruptedException e) {
                            logger.debug("The ChannelManager update thread has been interrupted => ", e);
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    logger.debug("The ChannelManager update thread has been interrupted => ", e);
                } catch (Exception e) {
                    logger.error("The ChannelManager thread created an unexpected exception => ", e);
                } finally {
                    updateRunning.set(false);
                }
            }
        });

        if (!Thread.currentThread().isInterrupted()) {
            updateThread.setName("ChannelManager-" + updateThread.getId());
            updateThread.start();
        }

        logger.exit();
    }

    public static boolean isUpdateChannelsThreadRunning() {
        return updateRunning.get();
    }

    public static void stopUpdateChannelsThread() {
        logger.entry();

        if (updateThread != null) {
            updateThread.interrupt();
        }

        logger.exit();
    }

    public void onSuspendEvent() {
        if (suspend.getAndSet(true)) {
            logger.error("onSuspendEvent: The computer is going into suspend mode and ChannelManager has possibly not recovered from the last suspend event.");
        } else {
            logger.debug("onSuspendEvent: Stopping services due to a suspend event.");

            stopUpdateChannelsThread();
            stopAllOfflineScansAndWait();
            //offlineScansMap.clear();
            offlineScanDevicesMap.clear();
        }
    }

    public void onResumeSuspendEvent() {
        if (!suspend.getAndSet(false)) {
            logger.error("onResumeSuspendEvent: The computer returned from suspend mode and ChannelManager possibly did not shutdown since the last suspend event.");
        } else {
            logger.debug("onResumeSuspendEvent: Starting services due to a resume event.");

            startUpdateChannelsThread();
        }
    }

    public void onResumeCriticalEvent() {
        if (!suspend.getAndSet(false)) {
            logger.error("onResumeCriticalEvent: The computer returned from suspend mode and ChannelManager possibly did not shutdown since the last suspend event.");
        } else {
            logger.debug("onResumeCriticalEvent: Starting services due to a resume event.");

            startUpdateChannelsThread();
        }
    }

    public void onResumeAutomaticEvent() {
        if (!suspend.getAndSet(false)) {
            logger.error("onResumeAutomaticEvent: The computer returned from suspend mode and ChannelManager possibly did not shutdown since the last suspend event.");
        } else {
            logger.debug("onResumeAutomaticEvent: Starting services due to a resume event.");

            startUpdateChannelsThread();
        }
    }
}
