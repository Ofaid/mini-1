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
import android.util.Log;

/** Restarts the dedicated radio UI when the service process stops refreshing its watchdog lease. */
public final class RadioProcessWatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = RadioProcessWatchdogReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !RadioProcessWatchdog.ACTION_LEASE_EXPIRED.equals(intent.getAction())
                || !RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.detectCurrent())) {
            return;
        }

        // Re-arm first so a blocked background launch is retried instead of silently abandoning
        // recovery. A healthy service heartbeat will move this lease forward again.
        RadioProcessWatchdog.arm(context);
        Log.w(TAG, "Radio watchdog lease expired; launching RadioShell");
        Intent launchIntent = new Intent(context, RadioShellActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(launchIntent);
        } catch (RuntimeException exception) {
            // The re-armed lease will retry. Newer Android builds may require a notification-based
            // foreground-service recovery path instead of a background Activity launch.
            Log.w(TAG, "Could not launch RadioShell from watchdog", exception);
        }
    }

}
