/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.service;

/** Pure validation and timing policy for the service-owned PTT watchdog. */
final class RadioPttWatchdogPolicy {
    static final int DEFAULT_MAXIMUM_TX_SECONDS = 120;
    private static final int MINIMUM_TX_SECONDS = 1;

    private RadioPttWatchdogPolicy() {
    }

    static int sanitizeMaximumSeconds(int maximumTxSeconds) {
        if (maximumTxSeconds < MINIMUM_TX_SECONDS
                || maximumTxSeconds > DEFAULT_MAXIMUM_TX_SECONDS) {
            return DEFAULT_MAXIMUM_TX_SECONDS;
        }
        return maximumTxSeconds;
    }

    static long delayMillis(int maximumTxSeconds) {
        return sanitizeMaximumSeconds(maximumTxSeconds) * 1000L;
    }

    static boolean shouldArm(boolean wasTalking, boolean isTalking) {
        return !wasTalking && isTalking;
    }

    static boolean shouldDisarm(boolean wasTalking, boolean isTalking) {
        return wasTalking && !isTalking;
    }
}
