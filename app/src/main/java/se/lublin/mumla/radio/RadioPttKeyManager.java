/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.SharedPreferences;
import android.view.KeyEvent;

import se.lublin.mumla.Settings;

/** Applies radio defaults and centralizes the multi-key PTT contract. */
public final class RadioPttKeyManager {
    private RadioPttKeyManager() {
    }

    /** Makes a managed radio usable without requiring an operator to visit Mumla settings. */
    public static void applyDefaults(SharedPreferences preferences) {
        String profile = RadioDeviceProfile.detectCurrent();
        if (!isRadioProfile(profile)) {
            return;
        }

        SharedPreferences.Editor editor = preferences.edit()
                .putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_PTT)
                .putBoolean(Settings.PREF_PTT_TOGGLE, false)
                .putBoolean(Settings.PREF_AUTO_RECONNECT, true)
                .putBoolean(Settings.PREF_PREPROCESSOR_ENABLED, true)
                .putBoolean(Settings.PREF_HALF_DUPLEX, true)
                .putBoolean(Settings.PREF_USE_TTS, true)
                .putBoolean(Settings.PREF_PTT_SOUND, false);
        if (RadioDeviceProfile.T99.equals(profile)
                || RadioDeviceProfile.T56.equals(profile)
                || RadioDeviceProfile.RYKS.equals(profile)
                || !preferences.contains(Settings.PREF_PUSH_KEY)) {
            int pttKeyCode = KeyEvent.KEYCODE_F1;
            if (RadioDeviceProfile.T56.equals(profile)) {
                pttKeyCode = RadioDeviceProfile.T56_PTT_KEY_CODE;
            } else if (RadioDeviceProfile.RYKS.equals(profile)) {
                pttKeyCode = RadioDeviceProfile.RYKS_PTT_KEY_CODE;
            }
            editor.putInt(Settings.PREF_PUSH_KEY, pttKeyCode);
        }
        editor.apply();
    }

    /** Returns true for the primary configured key and supported radio profile defaults. */
    public static boolean isConfiguredPttKey(int keyCode, Settings settings) {
        String profile = RadioDeviceProfile.detectCurrent();
        if (RadioDeviceProfile.T99.equals(profile)) {
            // Physical capture proves F2 is the labelled EXIT key on T99. Never allow a stale
            // preference to turn EXIT into PTT.
            return isProfileDefaultPttKey(profile, keyCode);
        }
        if (RadioDeviceProfile.T56.equals(profile)) {
            // T56 Menu is F1. Only the captured vendor DTT_PTT code may be the hardware PTT.
            return isProfileDefaultPttKey(profile, keyCode);
        }
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            return keyCode == RadioDeviceProfile.RYKS_PTT_KEY_CODE
                    || isMediaStyleKey(keyCode);
        }
        if (settings != null && keyCode == settings.getPushToTalkKey()) {
            return true;
        }
        return isProfileDefaultPttKey(profile, keyCode);
    }

    /** Raw Activity path, including scan-code disambiguation required by RYKS firmware. */
    public static boolean isConfiguredPttEvent(KeyEvent event, Settings settings) {
        if (event == null) {
            return false;
        }
        String profile = RadioDeviceProfile.detectCurrent();
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            if (isMediaStyleKey(event.getKeyCode())) {
                return true;
            }
            return isRyksPrimaryPttEvent(event.getKeyCode(), event.getScanCode());
        }
        return isConfiguredPttKey(event.getKeyCode(), settings);
    }

    static boolean isRyksPrimaryPttEvent(int keyCode, int scanCode) {
        return keyCode == RadioDeviceProfile.RYKS_PTT_KEY_CODE
                && (scanCode == RadioDeviceProfile.RYKS_PTT_SCAN_CODE
                || scanCode == RadioDeviceProfile.RYKS_SECONDARY_PTT_SCAN_CODE);
    }

    public static boolean isProfileDefaultPttKey(String profile, int keyCode) {
        if (RadioDeviceProfile.T99.equals(profile)) {
            return keyCode == KeyEvent.KEYCODE_F1 || isMediaStyleKey(keyCode);
        }
        if (RadioDeviceProfile.T56.equals(profile)) {
            return keyCode == RadioDeviceProfile.T56_PTT_KEY_CODE
                    || isMediaStyleKey(keyCode);
        }
        if (RadioDeviceProfile.RYKS.equals(profile)) {
            return keyCode == RadioDeviceProfile.RYKS_PTT_KEY_CODE
                    || isMediaStyleKey(keyCode);
        }
        return false;
    }

    public static boolean isMediaStyleKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_STOP
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
    }

    public static boolean isRadioProfile(String profile) {
        return RadioDeviceProfile.T99.equals(profile)
                || RadioDeviceProfile.T56.equals(profile)
                || RadioDeviceProfile.RYKS.equals(profile);
    }

    public static boolean shouldEnablePttConfirmationSound(String profile,
                                                           boolean preferenceEnabled) {
        return !isRadioProfile(profile) && preferenceEnabled;
    }

    public static boolean isDiagnosticHardwareKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_POWER
                || keyCode == KeyEvent.KEYCODE_F1
                || keyCode == KeyEvent.KEYCODE_F2
                || keyCode == KeyEvent.KEYCODE_F3
                || keyCode == KeyEvent.KEYCODE_F7
                || keyCode == KeyEvent.KEYCODE_F8
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_CALL
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_F11
                || keyCode == KeyEvent.KEYCODE_F12
                || keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT
                || keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_NAVIGATE_IN
                || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY
                || keyCode == KeyEvent.KEYCODE_STEM_1
                || keyCode == KeyEvent.KEYCODE_STEM_2
                || keyCode == KeyEvent.KEYCODE_STEM_3
                || isMediaStyleKey(keyCode);
    }
}
