/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.view.KeyEvent;

/** Timing and classification rules for deliberate radio-shell hardware actions. */
public final class RadioKeyActionPolicy {
    public static final long EXIT_HOLD_MS = 5_000L;
    public static final long ROOM_CHANGE_HOLD_MS = 1_000L;
    public static final long IDENTITY_HOLD_MS = 1_000L;

    private RadioKeyActionPolicy() {
    }

    /**
     * T99 labels do not match Android key names: MENU is DPAD_CENTER, EXIT is F2 and
     * the red key reaches apps as DPAD_RIGHT even though the kernel reports KEY_BACK.
     * Keep BACK as a compatibility path for firmware variants and injected diagnostics.
     */
    public static boolean isProtectedExitKey(String profile, int keyCode) {
        if (RadioDeviceProfile.T99.equals(profile)) {
            return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_F2
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        // T56 exposes a dedicated BACK control. RYKS red is native POWER and stays system-owned.
        return RadioDeviceProfile.T56.equals(profile) && keyCode == KeyEvent.KEYCODE_BACK;
    }

    public static boolean isRoomChangeKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    public static int roomDirection(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            return -1;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            return 1;
        }
        return 0;
    }

    public static boolean isRoomChangeEvent(String profile, KeyEvent event) {
        return roomDirection(profile, event) != 0;
    }

    /** Uses the two non-PTT side scans on RYKS for previous/next room selection. */
    public static int roomDirection(String profile, KeyEvent event) {
        if (event == null) {
            return 0;
        }
        return roomDirection(profile, event.getKeyCode(), event.getScanCode());
    }

    static int roomDirection(String profile, int keyCode, int scanCode) {
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            if (scanCode == RadioDeviceProfile.RYKS_SIDE_UP_SCAN_CODE) {
                return -1;
            }
            if (scanCode == RadioDeviceProfile.RYKS_SIDE_DOWN_SCAN_CODE) {
                return 1;
            }
        }
        return roomDirection(keyCode);
    }

    /** Maps the deliberately-held identity control for each captured radio profile. */
    public static boolean isIdentityToggleKey(String profile, int keyCode) {
        if (RadioDeviceProfile.T99.equals(profile)) {
            return keyCode == KeyEvent.KEYCODE_MENU;
        }
        if (RadioDeviceProfile.T56.equals(profile)) {
            return keyCode == KeyEvent.KEYCODE_DPAD_LEFT;
        }
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            return keyCode == RadioDeviceProfile.RYKS_MENU_KEY_CODE;
        }
        return false;
    }

    /** Handles OEM variants while keeping T99 OK (scan 353) distinct from green (scan 139). */
    public static boolean isIdentityToggleEvent(String profile, KeyEvent event) {
        if (event == null) {
            return false;
        }
        return isIdentityToggleEvent(profile, event.getKeyCode(), event.getScanCode());
    }

    static boolean isIdentityToggleEvent(String profile, int keyCode, int scanCode) {
        if (isIdentityToggleKey(profile, keyCode)) {
            return true;
        }
        if (RadioDeviceProfile.T99.equals(profile)) {
            return scanCode == 139;
        }
        if (RadioDeviceProfile.T56.equals(profile)) {
            return scanCode == 64;
        }
        return RadioDeviceProfile.RYKS.equals(profile)
                && scanCode == RadioDeviceProfile.RYKS_MENU_SCAN_CODE;
    }

    public static boolean heldLongEnough(long startedAt, long endedAt, long requiredDuration) {
        return startedAt >= 0L && endedAt >= startedAt
                && endedAt - startedAt >= requiredDuration;
    }
}
