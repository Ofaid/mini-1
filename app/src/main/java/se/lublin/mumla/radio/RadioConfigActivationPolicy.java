/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

/** Pure idle gate for applying a staged radio configuration. */
public final class RadioConfigActivationPolicy {
    private RadioConfigActivationPolicy() {
    }

    public static boolean canTrial(boolean serviceAvailable, boolean connectionTransitioning,
                                   boolean transmitting, boolean receiving) {
        return serviceAvailable && !connectionTransitioning && !transmitting && !receiving;
    }
}
