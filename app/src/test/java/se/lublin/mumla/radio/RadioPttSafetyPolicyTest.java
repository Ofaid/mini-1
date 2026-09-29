package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioPttSafetyPolicyTest {
    @Test
    public void managedRadioRequiresSynchronizationPttModeAndConfiguredRoom() {
        assertTrue(RadioPttSafetyPolicy.canStartTransmission(true, true, true, true));
        assertFalse(RadioPttSafetyPolicy.canStartTransmission(false, true, true, true));
        assertFalse(RadioPttSafetyPolicy.canStartTransmission(true, false, true, true));
        assertFalse(RadioPttSafetyPolicy.canStartTransmission(true, true, true, false));
    }

    @Test
    public void genericMumlaDoesNotRequireRadioShellRoomGate() {
        assertTrue(RadioPttSafetyPolicy.canStartTransmission(true, true, false, false));
    }
}
