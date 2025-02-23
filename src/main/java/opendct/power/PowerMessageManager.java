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

package opendct.power;

import opendct.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PowerMessageManager {
    private static final Logger logger = LogManager.getLogger(PowerMessageManager.class);

    public static final PowerMessagePump EVENTS = createPowerMessagePump();

    private static PowerMessagePump createPowerMessagePump() {
        logger.entry();
        // Since this is static, I'm initializing it here. I can't think of an easier way for this to be consistent
        // since the initialization is going to vary by OS.

        PowerMessagePump returnPump = null;

        switch (Config.OS_VERSION) {
            case WINDOWS:
                returnPump = new WindowsPowerMessagePump();
                break;
            case LINUX:
                returnPump = new LinuxPowerMessagePump();
                break;
            case MAC:
                returnPump = new MacPowerMessagePump();
                break;
            case UNKNOWN:
                break;
        }

        return logger.exit(returnPump);
    }
}
