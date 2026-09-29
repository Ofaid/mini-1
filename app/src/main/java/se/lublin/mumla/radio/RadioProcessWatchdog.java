/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * Maintains a process-independent recovery lease for dedicated radio profiles.
 *
 * The service refreshes the lease before it expires, so a healthy process never wakes the
 * receiver. If the process is killed, Android retains the last alarm and the receiver can reopen
 * the radio client without waiting for an OEM service-restart backoff.
 */
public final class RadioProcessWatchdog {
    public static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    public static final long LEASE_TIMEOUT_MS = 30_000L;
    static final String ACTION_LEASE_EXPIRED =
            "se.lublin.mumla.action.RADIO_PROCESS_WATCHDOG";
    private static final int REQUEST_CODE = 41031;

    private RadioProcessWatchdog() {
    }

    @SuppressLint("MissingPermission")
    public static void arm(Context context) {
        // Exact alarm APIs are confined to pre-S; S+ deliberately uses the inexact API.
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long triggerAt = SystemClock.elapsedRealtime() + LEASE_TIMEOUT_MS;
        PendingIntent recoveryIntent = recoveryIntent(appContext);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Avoid special exact-alarm permission on modern generic devices.
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, recoveryIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, recoveryIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt, recoveryIntent);
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, recoveryIntent);
        }
    }

    private static PendingIntent recoveryIntent(Context context) {
        Intent intent = new Intent(context, RadioProcessWatchdogReceiver.class)
                .setAction(ACTION_LEASE_EXPIRED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
