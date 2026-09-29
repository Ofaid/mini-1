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
import java.util.Collections;
import java.util.List;

/** Immutable visible RX state resolved from the service-owned talker snapshot. */
public final class RadioTrafficUiState {
    public enum Kind {
        READY,
        SINGLE_TALKER,
        MULTIPLE_TALKERS
    }

    private final Kind kind;
    private final List<String> talkers;
    private final String talker;

    private RadioTrafficUiState(Kind kind, List<String> talkers) {
        this.kind = kind;
        this.talkers = Collections.unmodifiableList(new ArrayList<>(talkers));
        this.talker = this.talkers.isEmpty() ? "" : this.talkers.get(0);
    }

    public static RadioTrafficUiState from(List<String> activeTalkers) {
        List<String> talkers = new ArrayList<>();
        if (activeTalkers != null) {
            for (String activeTalker : activeTalkers) {
                talkers.add(activeTalker == null ? "" : activeTalker);
            }
        }
        if (talkers.isEmpty()) {
            return new RadioTrafficUiState(Kind.READY, talkers);
        }
        return new RadioTrafficUiState(talkers.size() == 1
                ? Kind.SINGLE_TALKER : Kind.MULTIPLE_TALKERS, talkers);
    }

    public Kind getKind() {
        return kind;
    }

    public String getTalker() {
        return talker;
    }

    /** Returns the complete ordered snapshot used to render simultaneous talkers. */
    public List<String> getTalkers() {
        return talkers;
    }
}
