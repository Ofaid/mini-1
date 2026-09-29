/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.service;

import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;

/** Pure state policy for the controlled legacy external TALK interface. */
final class RadioExternalTalkPolicy {
    enum Decision {
        START,
        STOP,
        KEEP,
        REJECT,
        IGNORE
    }

    private RadioExternalTalkPolicy() {
    }

    static Decision decide(String status, boolean talking, boolean readyToTransmit,
                           boolean releaseRequired) {
        if (TalkBroadcastReceiver.TALK_STATUS_OFF.equals(status)) {
            return Decision.STOP;
        }
        if (TalkBroadcastReceiver.TALK_STATUS_TOGGLE.equals(status)
                && (talking || releaseRequired)) {
            return Decision.STOP;
        }
        boolean requestsStart = TalkBroadcastReceiver.TALK_STATUS_ON.equals(status)
                || TalkBroadcastReceiver.TALK_STATUS_TOGGLE.equals(status);
        if (!requestsStart) {
            return Decision.IGNORE;
        }
        if (talking) {
            return Decision.KEEP;
        }
        return readyToTransmit && !releaseRequired ? Decision.START : Decision.REJECT;
    }
}
