package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class RadioPttRecoveryGuardTest {
    @After
    public void clearGuard() {
        RadioPttRecoveryGuard.noteRelease();
    }

    @Test
    public void blocksUntilAnExplicitReleaseIsObserved() {
        assertFalse(RadioPttRecoveryGuard.isReleaseRequired());
        RadioPttRecoveryGuard.requireRelease();
        assertTrue(RadioPttRecoveryGuard.isReleaseRequired());
        RadioPttRecoveryGuard.noteRelease();
        assertFalse(RadioPttRecoveryGuard.isReleaseRequired());
    }
}
