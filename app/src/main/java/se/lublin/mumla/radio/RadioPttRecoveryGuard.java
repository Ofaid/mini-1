/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide latch preventing a PTT DOWN from crossing into a newly opened radio window. */
public final class RadioPttRecoveryGuard {
    private static final AtomicBoolean RELEASE_REQUIRED = new AtomicBoolean();

    private RadioPttRecoveryGuard() {
    }

    public static void requireRelease() {
        RELEASE_REQUIRED.set(true);
    }

    public static void noteRelease() {
        RELEASE_REQUIRED.set(false);
    }

    public static boolean isReleaseRequired() {
        return RELEASE_REQUIRED.get();
    }
}
