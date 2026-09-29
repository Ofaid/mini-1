/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import se.lublin.humla.model.TalkState;

/** Service-owned remote RX state used by config activation safety checks. */
public final class RadioReceiveTracker {
    private final Map<Integer, String> activeSessions = new LinkedHashMap<>();

    public synchronized void update(int session, String name, boolean self, TalkState state) {
        if (self || state == null || state == TalkState.PASSIVE) {
            activeSessions.remove(session);
        } else {
            activeSessions.put(session, name == null ? "" : name);
        }
    }

    public synchronized void remove(int session) {
        activeSessions.remove(session);
    }

    public synchronized void clear() {
        activeSessions.clear();
    }

    public synchronized boolean isReceiving() {
        return !activeSessions.isEmpty();
    }

    public synchronized List<String> getActiveTalkers() {
        return new ArrayList<>(activeSessions.values());
    }
}
