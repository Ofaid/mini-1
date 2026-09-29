package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class RadioPttKeyManagerTest {
    @Test
    public void recognizesMediaKeysForScreenOffSessionPath() {
        assertTrue(RadioPttKeyManager.isMediaStyleKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertTrue(RadioPttKeyManager.isMediaStyleKey(KeyEvent.KEYCODE_HEADSETHOOK));
        assertFalse(RadioPttKeyManager.isMediaStyleKey(KeyEvent.KEYCODE_F1));
    }

    @Test
    public void recognizesRadioProfilesForAutomaticDefaults() {
        assertTrue(RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.T99));
        assertTrue(RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.T56));
        assertTrue(RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.RYKS));
        assertFalse(RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.GENERIC));
    }

    @Test
    public void t99UsesCapturedF1PttAndRejectsPhysicalExitF2() {
        assertTrue(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_F1));
        assertFalse(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_F2));
        assertTrue(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
    }

    @Test
    public void t56UsesCapturedVendorPttAndRejectsMenuF1() {
        assertTrue(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.T56, RadioDeviceProfile.T56_PTT_KEY_CODE));
        assertFalse(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_F1));
    }

    @Test
    public void ryksAcceptsBothFirmwarePttScansAndRejectsTheF7SideKey() {
        assertTrue(RadioPttKeyManager.isRyksPrimaryPttEvent(
                RadioDeviceProfile.RYKS_PTT_KEY_CODE,
                RadioDeviceProfile.RYKS_PTT_SCAN_CODE));
        assertFalse(RadioPttKeyManager.isRyksPrimaryPttEvent(
                RadioDeviceProfile.RYKS_PTT_KEY_CODE,
                RadioDeviceProfile.RYKS_SIDE_DOWN_SCAN_CODE));
        assertTrue(RadioPttKeyManager.isRyksPrimaryPttEvent(
                RadioDeviceProfile.RYKS_PTT_KEY_CODE,
                RadioDeviceProfile.RYKS_SECONDARY_PTT_SCAN_CODE));
        assertTrue(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.RYKS, RadioDeviceProfile.RYKS_PTT_KEY_CODE));
        assertTrue(RadioPttKeyManager.isProfileDefaultPttKey(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_HEADSETHOOK));
    }

    @Test
    public void managedRadiosAlwaysSuppressNormalPttConfirmationSound() {
        assertFalse(RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.T99, true));
        assertFalse(RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.T56, true));
        assertFalse(RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.RYKS, true));
        assertTrue(RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.GENERIC, true));
        assertFalse(RadioPttKeyManager.shouldEnablePttConfirmationSound(
                RadioDeviceProfile.GENERIC, false));
    }

    @Test
    public void diagnosticsIncludeCapturedVendorKeys() {
        assertTrue(RadioPttKeyManager.isDiagnosticHardwareKey(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(RadioPttKeyManager.isDiagnosticHardwareKey(KeyEvent.KEYCODE_NAVIGATE_PREVIOUS));
    }
}
