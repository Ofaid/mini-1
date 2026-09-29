/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import se.lublin.humla.util.HumlaException;

public final class PendingConfigTrialPolicyTest {
    @Test
    public void transientConnectedFailuresKeepCandidateForRetry() {
        assertFalse(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR, true, 1));
        assertFalse(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR, true, 2));
    }

    @Test
    public void repeatedConnectedFailureRejectsCandidate() {
        assertTrue(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR, true, 3));
    }

    @Test
    public void offlineFailureWaitsForNetworkReturn() {
        assertFalse(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR, false, 99));
    }

    @Test
    public void permanentErrorsFailClosed() {
        assertTrue(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.REJECT, true, 0));
        assertTrue(PendingConfigTrialPolicy.shouldReject(
                HumlaException.HumlaDisconnectReason.OTHER_ERROR, true, 0));
    }
}
