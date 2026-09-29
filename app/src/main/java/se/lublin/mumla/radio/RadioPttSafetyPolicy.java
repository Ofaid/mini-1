/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

/** Pure readiness gate shared by every managed-radio PTT input path. */
public final class RadioPttSafetyPolicy {
    private RadioPttSafetyPolicy() {
    }

    public static boolean canStartTransmission(boolean synchronizedSession,
                                               boolean pttMode,
                                               boolean managedRadio,
                                               boolean configuredRoomReady) {
        return synchronizedSession && pttMode
                && (!managedRadio || configuredRoomReady);
    }
}
