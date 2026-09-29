/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import se.lublin.humla.util.HumlaException;

/** Fail-closed policy that tolerates only bounded transient config-trial dial failures. */
final class PendingConfigTrialPolicy {
    static final int MAX_CONNECTED_NETWORK_FAILURES = 3;

    private PendingConfigTrialPolicy() {
    }

    static boolean shouldReject(HumlaException.HumlaDisconnectReason reason,
                                boolean networkConnected, int connectedNetworkFailures) {
        if (reason == null) {
            return false;
        }
        switch (reason) {
            case REJECT:
            case OTHER_ERROR:
                return true;
            case CONNECTION_ERROR:
                return networkConnected
                        && connectedNetworkFailures >= MAX_CONNECTED_NETWORK_FAILURES;
            case USER_REMOVE:
            default:
                return false;
        }
    }
}
