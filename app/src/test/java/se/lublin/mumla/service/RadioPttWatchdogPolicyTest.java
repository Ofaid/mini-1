package se.lublin.mumla.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioPttWatchdogPolicyTest {
    @Test
    public void acceptsValidatedConfigBounds() {
        assertEquals(1, RadioPttWatchdogPolicy.sanitizeMaximumSeconds(1));
        assertEquals(120, RadioPttWatchdogPolicy.sanitizeMaximumSeconds(120));
        assertEquals(1_000L, RadioPttWatchdogPolicy.delayMillis(1));
        assertEquals(120_000L, RadioPttWatchdogPolicy.delayMillis(120));
    }

    @Test
    public void invalidValuesFailSafeToDefault() {
        assertEquals(120, RadioPttWatchdogPolicy.sanitizeMaximumSeconds(0));
        assertEquals(120, RadioPttWatchdogPolicy.sanitizeMaximumSeconds(-1));
        assertEquals(120, RadioPttWatchdogPolicy.sanitizeMaximumSeconds(121));
        assertEquals(120_000L, RadioPttWatchdogPolicy.delayMillis(Integer.MAX_VALUE));
    }

    @Test
    public void armsAndDisarmsOnlyOnTalkingTransitions() {
        assertTrue(RadioPttWatchdogPolicy.shouldArm(false, true));
        assertFalse(RadioPttWatchdogPolicy.shouldArm(true, true));
        assertFalse(RadioPttWatchdogPolicy.shouldArm(false, false));

        assertTrue(RadioPttWatchdogPolicy.shouldDisarm(true, false));
        assertFalse(RadioPttWatchdogPolicy.shouldDisarm(true, true));
        assertFalse(RadioPttWatchdogPolicy.shouldDisarm(false, false));
    }
}
