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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TVChannelImpl implements TVChannel {
    private static final Logger logger = LogManager.getLogger(TVChannelImpl.class);

    private boolean tunable = false;
    private boolean ignore = false;
    private CopyProtection cci = CopyProtection.UNKNOWN;
    private int signalStrength = 0;
    private String channelRemap = "";
    private final String channel;
    private final String name;
    private String url = "";
    private String modulation = "";
    private String frequency = "";
    private String program = "";
    private String eia = "";
    private String[] changes = new String[12];

    private static final int iChannel = 0;
    private static final int iChannelRemap = 1;
    private static final int iTunable = 2;
    private static final int iName = 3;
    private static final int iURL = 4;
    private static final int iModulation = 5;
    private static final int iFrequency = 6;
    private static final int iProgram = 7;
    private static final int iEIA = 8;
    private static final int iSignalStrength = 9;
    private static final int iCCI = 10;
    private static final int iIgnore = 11;

    public TVChannelImpl(String channel, String name) {
        this.channel = channel;
        this.name = name;
    }

    public TVChannelImpl(String properties[]) throws Exception {
        if (properties.length < 11) {
            throw new Exception("The provided array does not contain all parameters required for a channel.");
        }

        channel = properties[0];
        channelRemap = properties[1];
        tunable = Boolean.valueOf(properties[2]);
        name = properties[3];
        url = properties[4];
        modulation = properties[5];
        frequency = properties[6];
        program = properties[7];
        eia = properties[8];

        try {
            signalStrength = Integer.valueOf(properties[9]);
        } catch (NumberFormatException e) {
            logger.warn("Expected an integer, but '{}' was provided. Using the default 0 for signalStrength.", properties[9]);
            signalStrength = 0;
        }

        try {
            cci = CopyProtection.valueOf(properties[10]);
        } catch (IllegalArgumentException e) {
            logger.warn("Expected an copy protection enum, but '{}' was provided. Using the default UNKNOWN for cci.", properties[10]);
            cci = CopyProtection.UNKNOWN;
        }

        if (properties.length == 12) {
            ignore = Boolean.valueOf(properties[11]);
        }
    }

    public TVChannelImpl(String channel, String name, String url, boolean ignore) {
        this.channel = channel;
        this.name = name;
        this.url = url;
        this.ignore = ignore;
    }

    public TVChannelImpl(String channel, String name, String modulation, String frequency, String program, String eia, boolean ignore) {
        this.channel = channel;
        this.name = name;
        this.modulation = modulation;
        this.frequency = frequency;
        this.program = program;
        this.eia = eia;
        this.url = "";
        this.ignore = ignore;
    }

    public TVChannelImpl(String channel, String channelRemap, boolean tunable, String name, String url, String modulation, String frequency, String program, String eia, int signalStrength, CopyProtection cci, boolean ignore) {
        this.channel = channel;
        this.channelRemap = channelRemap;
        this.tunable = tunable;
        this.name = name;
        this.url = url;
        this.modulation = modulation;
        this.frequency = frequency;
        this.program = program;
        this.eia = eia;
        this.signalStrength = signalStrength;
        this.cci = cci;
        this.ignore = ignore;
    }

    public String[] getProperties() {
        return new String[]{
                channel,
                channelRemap,
                String.valueOf(tunable),
                name,
                url,
                modulation,
                frequency,
                program,
                eia,
                String.valueOf(signalStrength),
                cci.name(),
                String.valueOf(ignore)
        };
    }

    public boolean isTunable() {
        return tunable;
    }

    public void setTunable(boolean tunable) {
        this.tunable = tunable;
        changes[iTunable] = String.valueOf(tunable);
    }

    public void setModulation(String modulation) {
        this.modulation = modulation;
        changes[iModulation] = modulation;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
        changes[iFrequency] = frequency;
    }

    public void setProgram(String program) {
        this.program = program;
        changes[iProgram] = program;
    }

    public void setEia(String eia) {
        this.eia = eia;
        changes[iEIA] = eia;
    }

    public String getFrequency() {
        return frequency;
    }

    public String getProgram() {
        return program;
    }

    public String getChannel() {
        return channel;
    }

    public String getChannelRemap() {
        return channelRemap;
    }

    public void setChannelRemap(String channelRemap) {
        this.channelRemap = channelRemap;
        changes[iChannelRemap] = channelRemap;
    }

    public String getName() {
        return name;
    }

    public String getModulation() {
        return modulation;
    }

    public String getEia() {
        return eia;
    }

    public void setCci(CopyProtection cci) {
        this.cci = cci;
        changes[iCCI] = cci.name();
    }

    public CopyProtection getCci() {
        return cci;
    }

    public void setSignalStrength(int signalStrength) {
        this.signalStrength = signalStrength;
        changes[iSignalStrength] = String.valueOf(signalStrength);
    }

    public int getSignalStrength() {
        return signalStrength;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
        changes[iURL] = url;
    }

    public boolean isIgnore() {
        return ignore;
    }

    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
        changes[iIgnore] = String.valueOf(ignore);
    }

    public String[] getAndClearUpdates() {
        String oldChanges[] = changes;
        changes = new String[12];
        return oldChanges;
    }
}
