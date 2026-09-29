/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import se.lublin.mumla.radio.DeviceIdentityManager;
import se.lublin.mumla.radio.RadioDeviceProfile;
import se.lublin.mumla.service.MumlaService;

/** T56-only adaptive location and APRS-IS coordinator. */
public final class AprsTrackingManager {
    // Android 5.1 rejects log tags longer than 23 characters.
    private static final String TAG = "MinimumAprs";
    private static final int POLL_REQUEST_CODE = 5601;
    private static final long STATIONARY_POLL_MS = 30L * 60L * 1000L;
    private static final long STATIONARY_ACQUISITION_WINDOW_MS = 90L * 1000L;
    private static final long MOVING_POLL_MS = 5L * 60L * 1000L;
    private static final char APRS_SYMBOL_TABLE = '/';
    private static final char STATIONARY_SYMBOL_CODE = '-';
    private static final char WALKING_SYMBOL_CODE = '[';
    private static final char VEHICLE_SYMBOL_CODE = '>';
    private static final String PREF_LAST_LATITUDE = "tracking_last_latitude";
    private static final String PREF_LAST_LONGITUDE = "tracking_last_longitude";
    private static final String PREF_LAST_ACCURACY = "tracking_last_accuracy";
    private static final String PREF_LAST_WALL = "tracking_last_wall";
    private static final String PREF_LAST_STATE = "tracking_last_state";
    private static final String PREF_LAST_SUCCESS_WALL = "tracking_last_success_wall";
    private static final String PREF_LAST_OBJECT_NAME = "tracking_last_object_name";
    private static final String PREF_PACKET_FORMAT = "tracking_packet_format";
    private static final int POSITION_PACKET_FORMAT_VERSION = 5;

    private final Context context;
    private final LocationManager locationManager;
    private final AlarmManager alarmManager;
    private final HandlerThread locationThread;
    private final Handler handler;
    private final ExecutorService transportExecutor;
    private final AprsTransport transport;
    private final String defaultObjectName;
    private volatile String objectName;
    private final PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            try {
                int dbm = signalStrength == null ? AprsHealthSnapshot.UNKNOWN
                        : signalStrengthDbm(signalStrength);
                mobileRssiDbm = dbm == 0 || dbm == Integer.MAX_VALUE
                        ? AprsHealthSnapshot.UNKNOWN : dbm;
                mobileRssiElapsedRealtime = mobileRssiDbm == AprsHealthSnapshot.UNKNOWN
                        ? 0L : SystemClock.elapsedRealtime();
            } catch (RuntimeException ignored) {
                mobileRssiDbm = AprsHealthSnapshot.UNKNOWN;
                mobileRssiElapsedRealtime = 0L;
            }
        }
    };
    private final AprsBeaconCoordinator coordinator = new AprsBeaconCoordinator();
    private final Runnable stationaryAcquisitionTimeout = this::stopLocationUpdates;
    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleLocation(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
            Log.w(TAG, "location provider disabled: " + provider);
        }

        @Override
        public void onProviderEnabled(String provider) {
            Log.d(TAG, "location provider enabled: " + provider);
        }

        @Override
        public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
            // API-22 callback retained for the T56 platform.
        }
    };

    private volatile AprsTrackingConfig config = AprsTrackingConfig.disabled();
    private volatile boolean stopped;
    private final TrackingAttemptGate attemptGate = new TrackingAttemptGate();
    /** Accessed only on the location handler thread. */
    private long inFlightLogicalId = TrackingAttemptGate.NO_ATTEMPT;
    private volatile int mobileRssiDbm = AprsHealthSnapshot.UNKNOWN;
    private volatile long mobileRssiElapsedRealtime;
    private TelephonyManager telephonyManager;
    private AprsBeaconCoordinator.MovementState requestedState;

    public AprsTrackingManager(Context context) {
        this(context, new AprsIsHttpTransport(context));
    }

    AprsTrackingManager(Context context, AprsTransport transport) {
        if (context == null || transport == null) {
            throw new IllegalArgumentException("tracking dependencies must not be null");
        }
        this.context = context.getApplicationContext();
        this.transport = transport;
        this.defaultObjectName = AprsObjectName.fromDeviceId(new DeviceIdentityManager(
                PreferenceManager.getDefaultSharedPreferences(this.context)).getOrCreateDeviceId());
        this.objectName = defaultObjectName;
        this.locationManager = (LocationManager) this.context
                .getSystemService(Context.LOCATION_SERVICE);
        this.alarmManager = (AlarmManager) this.context
                .getSystemService(Context.ALARM_SERVICE);
        this.locationThread = new HandlerThread("minimum-t56-location");
        this.locationThread.start();
        this.handler = new Handler(locationThread.getLooper());
        this.transportExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "minimum-t56-aprs");
            thread.setDaemon(true);
            return thread;
        });
        restoreLastSuccessful();
    }

    public void reloadConfig(JSONObject root) {
        String hardwareProfile = RadioDeviceProfile.detectCurrent();
        if (!RadioDeviceProfile.T56.equals(hardwareProfile)) {
            return;
        }
        final AprsTrackingConfig loaded = parseConfigOrDisabled(root, hardwareProfile, true);
        final long reconfigurationTicket = attemptGate.beginReconfigure();
        handler.post(() -> {
            String nextObjectName = loaded.getObjectName().isEmpty()
                    ? defaultObjectName : loaded.getObjectName();
            boolean objectIdentityChanged = !nextObjectName.equals(objectName);
            boolean enabled = loaded.isEnabled() && loaded.isAprsEnabled();
            boolean applied = attemptGate.applyReconfiguration(reconfigurationTicket, enabled,
                    () -> {
                        if (inFlightLogicalId != TrackingAttemptGate.NO_ATTEMPT) {
                            // Lifecycle-owned abandonment, not a stale transport result.
                            coordinator.onPermanentFailure(inFlightLogicalId);
                            inFlightLogicalId = TrackingAttemptGate.NO_ATTEMPT;
                        }
                        if (objectIdentityChanged) {
                            objectName = nextObjectName;
                            coordinator.resetForObjectIdentity();
                            clearPersistedSuccess();
                        }
                        config = loaded;
                    });
            if (!applied) {
                return;
            }
            if (!config.isEnabled() || !config.isAprsEnabled()) {
                coordinator.resetForObjectIdentity();
                stopLocationUpdates();
                stopMobileSignalListener();
                cancelPoll();
                clearPersistedSuccess();
                Log.i(TAG, "T56 tracking is configured off");
                return;
            }
            if (!hasLocationPermission()) {
                Log.w(TAG, "T56 tracking disabled until location permission is granted");
                return;
            }
            startMobileSignalListener();
            requestLocationUpdates();
            sendReady();
        });
    }

    /**
     * Parses a tracking section using the same fail-closed policy as {@link #reloadConfig}.
     * Package-private visibility keeps this small policy independently unit-testable without
     * constructing Android location/telephony services in a JVM test.
     */
    static AprsTrackingConfig parseConfigOrDisabled(JSONObject root, String hardwareProfile) {
        return parseConfigOrDisabled(root, hardwareProfile, false);
    }

    private static AprsTrackingConfig parseConfigOrDisabled(JSONObject root, String hardwareProfile,
                                                             boolean logRejection) {
        try {
            return AprsTrackingConfig.fromJson(root, hardwareProfile);
        } catch (JSONException exception) {
            if (logRejection) {
                Log.w(TAG, "tracking config rejected: " + exception.getMessage());
            }
            return AprsTrackingConfig.disabled();
        }
    }

    public void onPoll() {
        if (!RadioDeviceProfile.T56.equals(RadioDeviceProfile.detectCurrent())) {
            return;
        }
        handler.post(() -> {
            if (!config.isEnabled() || !config.isAprsEnabled() || stopped) {
                return;
            }
            requestLocationUpdates();
            sendReady();
        });
    }

    public void onPttPressed() {
        if (!config.isEnabled() || !config.isAprsEnabled() || !config.isPttTriggered()) {
            return;
        }
        // This only evaluates the cached accepted fix; it never waits for or starts a GPS fix.
        handler.post(() -> {
            if (stopped || !config.isEnabled() || !config.isAprsEnabled()
                    || !config.isPttTriggered()
                    || attemptGate.beginAttempt() == TrackingAttemptGate.NO_ATTEMPT) {
                return;
            }
            AprsBeaconCoordinator.Decision decision = coordinator.onPtt(
                    System.currentTimeMillis(), SystemClock.elapsedRealtime());
            logDecision("PTT", decision);
            sendReady();
        });
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        attemptGate.stop();
        handler.post(() -> {
            stopLocationUpdates();
            stopMobileSignalListener();
            cancelPoll();
            locationThread.quitSafely();
        });
        transportExecutor.shutdownNow();
    }

    private void handleLocation(Location location) {
        if (stopped || location == null || !config.isEnabled() || !config.isAprsEnabled()) {
            return;
        }
        long elapsed = location.getElapsedRealtimeNanos() > 0L
                ? location.getElapsedRealtimeNanos() / 1_000_000L
                : SystemClock.elapsedRealtime();
        TrackingFix fix = new TrackingFix(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : TrackingFix.UNKNOWN,
                location.getTime(), elapsed,
                location.hasSpeed() ? location.getSpeed() : TrackingFix.UNKNOWN,
                location.hasBearing() ? location.getBearing() : TrackingFix.UNKNOWN);
        AprsBeaconCoordinator.Decision decision = coordinator.onLocation(fix,
                System.currentTimeMillis(), SystemClock.elapsedRealtime());
        logDecision("GPS", decision);
        AprsBeaconCoordinator.MovementState state = coordinator.getMovementState();
        if (state != requestedState) {
            requestedState = state;
            requestLocationUpdates();
        }
        sendReady();
    }

    private void sendReady() {
        if (stopped || !config.isAprsEnabled()) {
            return;
        }
        final long attempt = attemptGate.beginAttempt();
        if (attempt == TrackingAttemptGate.NO_ATTEMPT) {
            return;
        }
        AprsBeaconCoordinator.Beacon beacon = coordinator.takeReady(SystemClock.elapsedRealtime());
        if (beacon == null) {
            scheduleRetryIfNeeded();
            return;
        }
        inFlightLogicalId = beacon.getLogicalId();
        final AprsTrackingConfig packetConfig = config;
        final String packetObjectName = objectName;
        final String packet;
        try {
            String healthComment = AprsHealthSnapshot.capture(context, freshMobileRssiDbm())
                    .toAprsComment(beacon.getMovementState(), beacon.getFix().getAccuracyMeters());
            packet = AprsPacketEncoder.encodeObject(packetConfig.getSourceCallsign(), packetObjectName,
                    beacon.getFix(), APRS_SYMBOL_TABLE,
                    symbolCodeFor(beacon.getMovementState()), healthComment);
        } catch (RuntimeException exception) {
            Log.w(TAG, "APRS packet rejected before transport: " + exception.getClass().getSimpleName());
            attemptGate.runIfCurrent(attempt, () -> {
                coordinator.onSendFailure(beacon.getLogicalId(), false,
                        SystemClock.elapsedRealtime());
                inFlightLogicalId = TrackingAttemptGate.NO_ATTEMPT;
                scheduleRetryIfNeeded();
            });
            return;
        }
        try {
            transportExecutor.execute(() -> {
                AprsTransport.SendResult result;
                try {
                    result = transport.send(packetConfig, packet);
                } catch (RuntimeException exception) {
                    result = AprsTransport.SendResult.retryable(
                            "transport exception: " + exception.getClass().getSimpleName());
                }
                AprsTransport.SendResult finalResult = result;
                handler.post(() -> {
                    attemptGate.runIfCurrent(attempt, () -> {
                        long now = SystemClock.elapsedRealtime();
                        if (finalResult.getStatus() == AprsTransport.SendResult.Status.SUCCESS) {
                            boolean resultApplied = coordinator.onSendSuccess(
                                    beacon.getLogicalId(), now);
                            if (resultApplied && packetObjectName.equals(objectName)) {
                                persistSuccess(beacon, now, packetObjectName);
                                Log.i(TAG, "APRS position accepted by send-only server");
                            } else {
                                Log.i(TAG, "APRS receipt ignored after Object identity changed");
                            }
                        } else if (finalResult.getStatus()
                                == AprsTransport.SendResult.Status.PERMANENT_FAILURE) {
                            coordinator.onPermanentFailure(beacon.getLogicalId());
                            Log.w(TAG, "APRS send disabled until configuration changes: "
                                    + finalResult.getDetail());
                        } else {
                            coordinator.onSendFailure(beacon.getLogicalId(),
                                    finalResult.getStatus()
                                            == AprsTransport.SendResult.Status.UNCERTAIN_DELIVERY,
                                    now);
                            Log.w(TAG, "APRS send failed: " + finalResult.getDetail());
                        }
                        inFlightLogicalId = TrackingAttemptGate.NO_ATTEMPT;
                        scheduleRetryIfNeeded();
                    });
                });
            });
        } catch (RejectedExecutionException ignored) {
            attemptGate.runIfCurrent(attempt, () -> {
                coordinator.onSendFailure(beacon.getLogicalId(), false,
                        SystemClock.elapsedRealtime());
                inFlightLogicalId = TrackingAttemptGate.NO_ATTEMPT;
                scheduleRetryIfNeeded();
            });
        }
    }

    private static char symbolCodeFor(AprsBeaconCoordinator.MovementState state) {
        if (state == AprsBeaconCoordinator.MovementState.VEHICLE) {
            return VEHICLE_SYMBOL_CODE;
        }
        if (state == AprsBeaconCoordinator.MovementState.WALKING) {
            return WALKING_SYMBOL_CODE;
        }
        return STATIONARY_SYMBOL_CODE;
    }

    private void scheduleRetryIfNeeded() {
        long nextAttempt = coordinator.getNextAttemptElapsedRealtime();
        if (nextAttempt < 0L) {
            return;
        }
        long delay = Math.max(1_000L, nextAttempt - SystemClock.elapsedRealtime());
        handler.postDelayed(this::sendReady, delay);
    }

    private void requestLocationUpdates() {
        if (!hasLocationPermission() || locationManager == null || stopped
                || !config.isEnabled() || !config.isAprsEnabled()) {
            return;
        }
        handler.removeCallbacks(stationaryAcquisitionTimeout);
        stopLocationUpdates();
        AprsBeaconCoordinator.MovementState state = coordinator.getMovementState();
        requestedState = state;
        try {
            if (state == AprsBeaconCoordinator.MovementState.STATIONARY) {
                if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                            15L * 60L * 1000L, 100.0f, listener, handler.getLooper());
                }
                if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    // T56 firmware does not reliably deliver callbacks from requestSingleUpdate.
                    // Use a short-lived regular request; stationaryAcquisitionTimeout removes it
                    // after the bounded 90-second GPS window.
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            1_000L, 0.0f, listener, handler.getLooper());
                }
                handler.postDelayed(stationaryAcquisitionTimeout,
                        STATIONARY_ACQUISITION_WINDOW_MS);
                schedulePoll(STATIONARY_POLL_MS);
            } else if (state == AprsBeaconCoordinator.MovementState.WALKING) {
                if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            20_000L, 15.0f, listener, handler.getLooper());
                }
                schedulePoll(MOVING_POLL_MS);
            } else {
                if (isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                            8_000L, 20.0f, listener, handler.getLooper());
                }
                schedulePoll(MOVING_POLL_MS);
            }
        } catch (SecurityException exception) {
            Log.w(TAG, "T56 location request denied by platform");
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "T56 location provider unavailable");
        }
    }

    private boolean isProviderEnabled(String provider) {
        try {
            return locationManager != null && locationManager.isProviderEnabled(provider);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean hasLocationPermission() {
        return Build.VERSION.SDK_INT < 23
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void stopLocationUpdates() {
        if (locationManager == null) {
            return;
        }
        try {
            // Unregistration is required even after permission is revoked. The permission lint is
            // intentionally suppressed for this cleanup-only call; a concurrent/revoked denial is
            // harmless and caught below.
            locationManager.removeUpdates(listener);
        } catch (SecurityException ignored) {
            // Permission can be revoked before or during listener removal.
        }
    }

    private void startMobileSignalListener() {
        if (telephonyManager == null) {
            telephonyManager = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }
        if (telephonyManager == null) {
            return;
        }
        mobileRssiDbm = AprsHealthSnapshot.UNKNOWN;
        mobileRssiElapsedRealtime = 0L;
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
        } catch (SecurityException ignored) {
            mobileRssiDbm = AprsHealthSnapshot.UNKNOWN;
        }
    }

    private static int signalStrengthDbm(SignalStrength signalStrength) {
        Integer reflectedDbm = null;
        try {
            java.lang.reflect.Method method = SignalStrength.class.getMethod("getDbm");
            Object value = method.invoke(signalStrength);
            if (value instanceof Integer) {
                reflectedDbm = (Integer) value;
            }
        } catch (Exception ignored) {
            // API-22 has no public getDbm method on all vendor builds.
        }
        try {
            int asu = signalStrength.getGsmSignalStrength();
            return normalizeSignalDbm(reflectedDbm, asu);
        } catch (RuntimeException ignored) {
            return AprsHealthSnapshot.UNKNOWN;
        }
    }

    static int normalizeSignalDbm(Integer reflectedDbm, int gsmAsu) {
        if (reflectedDbm != null && reflectedDbm >= -140 && reflectedDbm <= -40) {
            return reflectedDbm;
        }
        return gsmAsu >= 0 && gsmAsu < 32
                ? -113 + (2 * gsmAsu) : AprsHealthSnapshot.UNKNOWN;
    }

    private int freshMobileRssiDbm() {
        long age = SystemClock.elapsedRealtime() - mobileRssiElapsedRealtime;
        return mobileRssiElapsedRealtime > 0L && age >= 0L && age <= 2L * 60L * 1000L
                ? mobileRssiDbm : AprsHealthSnapshot.UNKNOWN;
    }

    private void stopMobileSignalListener() {
        if (telephonyManager == null) {
            return;
        }
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        } catch (SecurityException ignored) {
            // Nothing to unregister when phone state permission was revoked.
        }
        mobileRssiDbm = AprsHealthSnapshot.UNKNOWN;
        mobileRssiElapsedRealtime = 0L;
    }

    private void schedulePoll(long delayMillis) {
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = pollIntent();
        long triggerAt = SystemClock.elapsedRealtime() + delayMillis;
        if (Build.VERSION.SDK_INT >= 19) {
            alarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt,
                    Math.min(5L * 60L * 1000L, Math.max(60_000L, delayMillis / 3)),
                    pendingIntent);
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private void cancelPoll() {
        if (alarmManager != null) {
            alarmManager.cancel(pollIntent());
        }
    }

    private PendingIntent pollIntent() {
        Intent intent = new Intent(context, MumlaService.class)
                .setAction(MumlaService.ACTION_RADIO_TRACKING_POLL);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(context, POLL_REQUEST_CODE, intent, flags);
    }

    private void restoreLastSuccessful() {
        android.content.SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        if (preferences.getInt(PREF_PACKET_FORMAT, 0) != POSITION_PACKET_FORMAT_VERSION) {
            return;
        }
        if (!preferences.contains(PREF_LAST_LATITUDE)
                || !preferences.contains(PREF_LAST_LONGITUDE)) {
            return;
        }
        long wall = preferences.getLong(PREF_LAST_WALL, 0L);
        long successWall = preferences.getLong(PREF_LAST_SUCCESS_WALL, wall);
        long age = successWall <= 0L ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - successWall);
        if (age > 24L * 60L * 60L * 1000L) {
            return;
        }
        long elapsed = Math.max(1L, SystemClock.elapsedRealtime() - age);
        TrackingFix fix = new TrackingFix(
                Double.longBitsToDouble(preferences.getLong(PREF_LAST_LATITUDE, 0L)),
                Double.longBitsToDouble(preferences.getLong(PREF_LAST_LONGITUDE, 0L)),
                preferences.getFloat(PREF_LAST_ACCURACY, TrackingFix.UNKNOWN),
                wall, elapsed, TrackingFix.UNKNOWN, TrackingFix.UNKNOWN);
        AprsBeaconCoordinator.MovementState state;
        try {
            state = AprsBeaconCoordinator.MovementState.valueOf(
                    preferences.getString(PREF_LAST_STATE,
                            AprsBeaconCoordinator.MovementState.STATIONARY.name()));
        } catch (IllegalArgumentException exception) {
            state = AprsBeaconCoordinator.MovementState.STATIONARY;
        }
        coordinator.restoreLastSuccessful(fix, state, elapsed);
        String persistedObjectName = preferences.getString(PREF_LAST_OBJECT_NAME, "");
        if (persistedObjectName.length() == AprsObjectName.APRS_OBJECT_NAME_LENGTH) {
            objectName = persistedObjectName;
        }
        Log.i(TAG, "restored previously accepted APRS position");
    }

    private void persistSuccess(AprsBeaconCoordinator.Beacon beacon, long nowElapsed,
                                String packetObjectName) {
        TrackingFix fix = beacon.getFix();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(PREF_LAST_LATITUDE, Double.doubleToRawLongBits(fix.getLatitude()))
                .putLong(PREF_LAST_LONGITUDE, Double.doubleToRawLongBits(fix.getLongitude()))
                .putFloat(PREF_LAST_ACCURACY, fix.getAccuracyMeters())
                .putLong(PREF_LAST_WALL, fix.getWallTimeMillis())
                .putLong(PREF_LAST_SUCCESS_WALL, System.currentTimeMillis())
                .putString(PREF_LAST_OBJECT_NAME, packetObjectName)
                .putString(PREF_LAST_STATE, beacon.getMovementState().name())
                .putInt(PREF_PACKET_FORMAT, POSITION_PACKET_FORMAT_VERSION)
                .apply();
    }

    private void clearPersistedSuccess() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_LAST_LATITUDE)
                .remove(PREF_LAST_LONGITUDE)
                .remove(PREF_LAST_ACCURACY)
                .remove(PREF_LAST_WALL)
                .remove(PREF_LAST_SUCCESS_WALL)
                .remove(PREF_LAST_OBJECT_NAME)
                .remove(PREF_LAST_STATE)
                .remove(PREF_PACKET_FORMAT)
                .apply();
    }

    private static void logDecision(String source, AprsBeaconCoordinator.Decision decision) {
        Log.d(TAG, source + " location=" + decision.isLocationAccepted()
                + " beacon=" + decision.isBeaconQueued()
                + " state=" + decision.getMovementState()
                + " reason=" + decision.getTriggerReason()
                + " detail=" + decision.getDetail());
    }
}
