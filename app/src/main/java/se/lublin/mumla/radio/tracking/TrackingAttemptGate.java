/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

/** Linearizes asynchronous tracking results against config reload and manager shutdown. */
final class TrackingAttemptGate {
    static final long NO_ATTEMPT = -1L;

    private long generation = 1L;
    private boolean active = true;

    synchronized long beginAttempt() {
        return active ? generation : NO_ATTEMPT;
    }

    synchronized long beginReconfigure() {
        active = false;
        return ++generation;
    }

    synchronized boolean applyReconfiguration(long ticket, boolean enableAttempts,
                                               Runnable configurationCommit) {
        if (ticket != generation) {
            return false;
        }
        configurationCommit.run();
        active = enableAttempts;
        return true;
    }

    synchronized boolean runIfCurrent(long attempt, Runnable resultCommit) {
        if (!active || attempt != generation) {
            return false;
        }
        resultCommit.run();
        return true;
    }

    synchronized void stop() {
        active = false;
        generation++;
    }
}
