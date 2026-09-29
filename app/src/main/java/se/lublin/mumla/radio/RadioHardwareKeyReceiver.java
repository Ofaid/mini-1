/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import se.lublin.mumla.service.MumlaService;

/** Receives OEM PTT broadcasts that remain available while keyguard owns the physical key. */
public final class RadioHardwareKeyReceiver extends BroadcastReceiver {
    public static final String ACTION_T56_PTT_DOWN = "unipro.hotkey.ptt.down";
    public static final String ACTION_T56_PTT_UP = "unipro.hotkey.ptt.up";
    public static final String ACTION_T56_IDENTITY_LONG = "unipro.hotkey.p2.long";
    public static final String ACTION_RYKS_PTT_DOWN = "com.zello.ptt.down";
    public static final String ACTION_RYKS_PTT_UP = "com.zello.ptt.up";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String profile = RadioDeviceProfile.detectCurrent();
        String action = intent.getAction();
        if (RadioDeviceProfile.T56.equals(profile)
                && ACTION_T56_IDENTITY_LONG.equals(action)) {
            try {
                context.startActivity(new Intent(context, RadioShellActivity.class)
                        .putExtra(RadioShellActivity.EXTRA_TOGGLE_IDENTITY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            } catch (RuntimeException ignored) {
                // The next deliberate hold can retry after a transient Activity-start failure.
            }
            return;
        }
        String serviceAction;
        if (isPttDownAction(profile, action)) {
            serviceAction = MumlaService.ACTION_RADIO_PTT_DOWN;
        } else if (isPttUpAction(profile, action)) {
            serviceAction = MumlaService.ACTION_RADIO_PTT_UP;
        } else {
            return;
        }
        // Prefer the already-running service. On Android 8+ a background receiver can be
        // rejected by startService even though the managed radio service is alive and foreground.
        if (MumlaService.dispatchRadioPttAction(serviceAction)) {
            return;
        }
        try {
            context.startService(new Intent(context, MumlaService.class).setAction(serviceAction));
        } catch (RuntimeException ignored) {
            // The next hardware press can retry after a transient service-start failure.
        }
    }

    static boolean isPttDownAction(String profile, String action) {
        return (RadioDeviceProfile.T56.equals(profile) && ACTION_T56_PTT_DOWN.equals(action))
                || (RadioDeviceProfile.RYKS.equals(profile) && ACTION_RYKS_PTT_DOWN.equals(action));
    }

    static boolean isPttUpAction(String profile, String action) {
        return (RadioDeviceProfile.T56.equals(profile) && ACTION_T56_PTT_UP.equals(action))
                || (RadioDeviceProfile.RYKS.equals(profile) && ACTION_RYKS_PTT_UP.equals(action));
    }
}
