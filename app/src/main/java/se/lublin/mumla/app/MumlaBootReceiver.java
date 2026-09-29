/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import se.lublin.mumla.Settings;
import se.lublin.mumla.radio.RadioDeviceProfile;
import se.lublin.mumla.radio.RadioPttKeyManager;
import se.lublin.mumla.radio.RadioProcessWatchdog;
import se.lublin.mumla.radio.RadioShellActivity;

/** Opens the radio client after boot on hardware that permits boot-time activity launches. */
public class MumlaBootReceiver extends BroadcastReceiver {
    private static final String TAG = MumlaBootReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        if (!Settings.getInstance(context).isAutoStartEnabled()) {
            return;
        }

        String profile = RadioDeviceProfile.detectCurrent();
        boolean radioProfile = RadioPttKeyManager.isRadioProfile(profile);
        if (radioProfile) {
            RadioProcessWatchdog.arm(context);
        }
        Class<?> launchClass = radioProfile ? RadioShellActivity.class : MumlaActivity.class;
        Intent launchIntent = new Intent(context, launchClass);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launchIntent);
        } catch (RuntimeException exception) {
            // Some newer Android/OEM builds block background activity launches. Keep this quiet
            // and let the future foreground-service notification path handle those devices.
            Log.w(TAG, "Could not launch Mumla after boot", exception);
        }
    }
}
