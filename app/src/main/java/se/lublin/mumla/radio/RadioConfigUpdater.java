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
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Schedules quiet config refreshes and stages validated candidates for idle radio activation. */
public final class RadioConfigUpdater {
    public static final String ACTION_CONFIG_PENDING =
            "se.lublin.mumla.action.RADIO_CONFIG_PENDING";

    private static final String TAG = RadioConfigUpdater.class.getName();
    private static final String PREF_LAST_SUCCESS = "radio_config_last_success_ms";
    private static final long REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean FORCE_REFRESH_PENDING = new AtomicBoolean(false);
    private static final Object NETWORK_MONITOR_LOCK = new Object();

    private static boolean networkMonitorRegistered;
    private static boolean lastNetworkConnected;

    private RadioConfigUpdater() {
    }

    /** Refreshes at every process start, then watches network return and the six-hour interval. */
    public static void start(Context context) {
        Context applicationContext = context.getApplicationContext();
        registerNetworkReturnMonitor(applicationContext);
        schedule(applicationContext, true);
    }

    public static void schedule(Context context) {
        schedule(context, false);
    }

    /** Returns only the last successful managed refresh time for protected provisioning status. */
    static long getLastSuccess(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
                .getLong(PREF_LAST_SUCCESS, 0L);
    }

    static boolean shouldRefresh(long now, long lastSuccess, boolean force) {
        return force || lastSuccess <= 0L || now - lastSuccess >= REFRESH_INTERVAL_MS;
    }

    private static void schedule(Context context, boolean force) {
        Context applicationContext = context.getApplicationContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext);
        long now = System.currentTimeMillis();
        long lastSuccess = preferences.getLong(PREF_LAST_SUCCESS, 0L);
        if (!shouldRefresh(now, lastSuccess, force)) {
            return;
        }
        if (!REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            if (force) {
                FORCE_REFRESH_PENDING.set(true);
            }
            return;
        }

        new Thread(() -> {
            try {
                String deviceId = new DeviceIdentityManager(preferences).getOrCreateDeviceId();
                String modelProfile = RadioDeviceProfile.detectCurrent();
                RadioConfigRepository repository = new RadioConfigRepository(applicationContext);
                repository.refresh(deviceId, modelProfile);
                preferences.edit().putLong(PREF_LAST_SUCCESS,
                        System.currentTimeMillis()).apply();
                if (repository.hasPending()) {
                    Intent available = new Intent(ACTION_CONFIG_PENDING)
                            .setPackage(applicationContext.getPackageName());
                    applicationContext.sendBroadcast(available);
                }
            } catch (RadioConfigRepository.DeviceConfigUnavailableException exception) {
                // An unknown Device ID leaves the current Last Known Good config untouched.
                Log.w(TAG, "Radio config refresh skipped; device configuration unavailable");
            } catch (IOException | JSONException | RuntimeException exception) {
                // Remote config is optional. The repository's embedded/cache fallback remains the
                // source of truth when the network is unavailable or the response is unsafe.
                Log.w(TAG, "Radio config refresh skipped; using local configuration ("
                        + exception.getClass().getSimpleName() + ")");
            } finally {
                REFRESH_IN_FLIGHT.set(false);
                if (FORCE_REFRESH_PENDING.getAndSet(false)) {
                    schedule(applicationContext, true);
                }
            }
        }, "minimum-radio-config").start();
    }

    private static void registerNetworkReturnMonitor(Context applicationContext) {
        synchronized (NETWORK_MONITOR_LOCK) {
            if (networkMonitorRegistered) {
                return;
            }
            lastNetworkConnected = isNetworkConnected(applicationContext);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean connected = isNetworkConnected(applicationContext);
                    boolean returned;
                    synchronized (NETWORK_MONITOR_LOCK) {
                        returned = connected && !lastNetworkConnected;
                        lastNetworkConnected = connected;
                    }
                    if (returned) {
                        schedule(applicationContext, true);
                    }
                }
            };
            applicationContext.registerReceiver(receiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            networkMonitorRegistered = true;
        }
    }

    static boolean isNetworkConnected(Context context) {
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected();
    }
}
