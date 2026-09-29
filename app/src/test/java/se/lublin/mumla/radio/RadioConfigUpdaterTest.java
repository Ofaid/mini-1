package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioConfigUpdaterTest {
    @Test
    public void refreshesOnFirstRunIntervalOrForcedNetworkReturn() {
        long now = 10L * 60L * 60L * 1000L;
        assertTrue(RadioConfigUpdater.shouldRefresh(now, 0L, false));
        assertFalse(RadioConfigUpdater.shouldRefresh(now,
                now - (60L * 60L * 1000L), false));
        assertTrue(RadioConfigUpdater.shouldRefresh(now,
                now - (7L * 60L * 60L * 1000L), false));
        assertTrue(RadioConfigUpdater.shouldRefresh(now, now, true));
    }
}
