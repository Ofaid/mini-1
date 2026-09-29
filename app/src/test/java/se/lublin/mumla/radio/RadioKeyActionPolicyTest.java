package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class RadioKeyActionPolicyTest {
    @Test
    public void t99RequiresFiveSecondHoldForEveryPhysicalExitPath() {
        assertEquals(5_000L, RadioKeyActionPolicy.EXIT_HOLD_MS);
        assertTrue(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_DPAD_CENTER));
        assertTrue(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_F2));
        assertTrue(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_BACK));
        assertTrue(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertFalse(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_MENU));
        assertTrue(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_BACK));
        assertFalse(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_F2));
        assertFalse(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_BACK));
        assertFalse(RadioKeyActionPolicy.isProtectedExitKey(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_POWER));
    }

    @Test
    public void upAndDownRequireOneSecondAndResolveDirection() {
        assertEquals(1_000L, RadioKeyActionPolicy.ROOM_CHANGE_HOLD_MS);
        assertTrue(RadioKeyActionPolicy.isRoomChangeKey(KeyEvent.KEYCODE_DPAD_UP));
        assertTrue(RadioKeyActionPolicy.isRoomChangeKey(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(-1, RadioKeyActionPolicy.roomDirection(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(1, RadioKeyActionPolicy.roomDirection(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(0, RadioKeyActionPolicy.roomDirection(KeyEvent.KEYCODE_MENU));
    }

    @Test
    public void identityToggleUsesCapturedProfileKeys() {
        assertEquals(1_000L, RadioKeyActionPolicy.IDENTITY_HOLD_MS);
        assertTrue(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_MENU));
        assertTrue(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_DPAD_LEFT));
        assertFalse(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_STEM_PRIMARY));
        assertFalse(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_STEM_2));
        assertTrue(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_F2));
        assertFalse(RadioKeyActionPolicy.isIdentityToggleKey(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_MENU));
    }

    @Test
    public void ryksF7SideButtonMovesToTheNextRoomWithoutUsingPttScans() {
        assertEquals(-1, RadioKeyActionPolicy.roomDirection(RadioDeviceProfile.RYKS,
                KeyEvent.KEYCODE_F8, RadioDeviceProfile.RYKS_SIDE_UP_SCAN_CODE));
        assertEquals(1, RadioKeyActionPolicy.roomDirection(RadioDeviceProfile.RYKS,
                KeyEvent.KEYCODE_F7, RadioDeviceProfile.RYKS_SIDE_DOWN_SCAN_CODE));
        assertEquals(0, RadioKeyActionPolicy.roomDirection(RadioDeviceProfile.RYKS,
                KeyEvent.KEYCODE_UNKNOWN, RadioDeviceProfile.RYKS_PTT_SCAN_CODE));
        assertEquals(0, RadioKeyActionPolicy.roomDirection(RadioDeviceProfile.RYKS,
                RadioDeviceProfile.RYKS_PTT_KEY_CODE,
                RadioDeviceProfile.RYKS_SECONDARY_PTT_SCAN_CODE));
    }

    @Test
    public void identityToggleAcceptsCapturedScanCodesAcrossOemKeyCodeVariants() {
        assertTrue(RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_DPAD_CENTER, 139));
        assertFalse(RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.T99, KeyEvent.KEYCODE_DPAD_CENTER, 353));
        assertTrue(RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_UNKNOWN, 64));
        assertFalse(RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.T56, KeyEvent.KEYCODE_UNKNOWN, 63));
        assertTrue(RadioKeyActionPolicy.isIdentityToggleEvent(
                RadioDeviceProfile.RYKS, KeyEvent.KEYCODE_F2,
                RadioDeviceProfile.RYKS_MENU_SCAN_CODE));
    }

    @Test
    public void releaseTimestampCompletesHoldEvenWhenHandlerWasDelayed() {
        assertTrue(RadioKeyActionPolicy.heldLongEnough(10_000L, 15_060L, 5_000L));
        assertFalse(RadioKeyActionPolicy.heldLongEnough(10_000L, 14_999L, 5_000L));
        assertFalse(RadioKeyActionPolicy.heldLongEnough(-1L, 15_060L, 5_000L));
    }
}
