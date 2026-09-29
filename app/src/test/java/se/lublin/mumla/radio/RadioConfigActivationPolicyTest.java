package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioConfigActivationPolicyTest {
    @Test
    public void allowsOnlyAvailableStableIdleRadio() {
        assertTrue(RadioConfigActivationPolicy.canTrial(true, false, false, false));
        assertFalse(RadioConfigActivationPolicy.canTrial(false, false, false, false));
        assertFalse(RadioConfigActivationPolicy.canTrial(true, true, false, false));
        assertFalse(RadioConfigActivationPolicy.canTrial(true, false, true, false));
        assertFalse(RadioConfigActivationPolicy.canTrial(true, false, false, true));
        assertFalse(RadioConfigActivationPolicy.canTrial(true, false, true, true));
    }
}
