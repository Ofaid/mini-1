/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class RadioProcessWatchdogTest {
    @Test
    public void leaseAllowsMultipleHeartbeatOpportunities() {
        assertTrue(RadioProcessWatchdog.LEASE_TIMEOUT_MS
                >= RadioProcessWatchdog.HEARTBEAT_INTERVAL_MS * 2L);
    }
}
