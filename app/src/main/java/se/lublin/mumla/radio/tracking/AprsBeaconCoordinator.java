/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import java.util.Locale;

/**
 * Thread-safe SmartBeacon and semantic duplicate-suppression pipeline.
 *
 * Every location/PTT/retry source enters here before transport. The coordinator retains at most
 * one in-flight and one newest pending logical beacon, so reconnects cannot replay a history.
 */
public final class AprsBeaconCoordinator {
    public enum MovementState {
        STATIONARY,
        WALKING,
        VEHICLE
    }

    public enum TriggerReason {
        FIRST_FIX,
        MOVEMENT,
        TURN,
        STARTED_MOVING,
        STOPPED,
        HEARTBEAT,
        PTT
    }

    public static final class Decision {
        private final boolean locationAccepted;
        private final boolean beaconQueued;
        private final String detail;
        private final MovementState movementState;
        private final TriggerReason triggerReason;

        private Decision(boolean locationAccepted, boolean beaconQueued, String detail,
                         MovementState movementState, TriggerReason triggerReason) {
            this.locationAccepted = locationAccepted;
            this.beaconQueued = beaconQueued;
            this.detail = detail;
            this.movementState = movementState;
            this.triggerReason = triggerReason;
        }

        public boolean isLocationAccepted() {
            return locationAccepted;
        }

        public boolean isBeaconQueued() {
            return beaconQueued;
        }

        public String getDetail() {
            return detail;
        }

        public MovementState getMovementState() {
            return movementState;
        }

        public TriggerReason getTriggerReason() {
            return triggerReason;
        }
    }

    public static final class Beacon {
        private final long logicalId;
        private final TrackingFix fix;
        private final MovementState movementState;
        private final TriggerReason triggerReason;
        private final int attempt;
        private final long nextAttemptElapsedRealtime;

        private Beacon(long logicalId, TrackingFix fix, MovementState movementState,
                       TriggerReason triggerReason, int attempt,
                       long nextAttemptElapsedRealtime) {
            this.logicalId = logicalId;
            this.fix = fix;
            this.movementState = movementState;
            this.triggerReason = triggerReason;
            this.attempt = attempt;
            this.nextAttemptElapsedRealtime = nextAttemptElapsedRealtime;
        }

        public long getLogicalId() {
            return logicalId;
        }

        public TrackingFix getFix() {
            return fix;
        }

        public MovementState getMovementState() {
            return movementState;
        }

        public TriggerReason getTriggerReason() {
            return triggerReason;
        }

        public int getAttempt() {
            return attempt;
        }

        public long getNextAttemptElapsedRealtime() {
            return nextAttemptElapsedRealtime;
        }

        private Beacon retryAt(long when) {
            return new Beacon(logicalId, fix, movementState, triggerReason,
                    attempt + 1, when);
        }
    }

    private static final long MAX_FIX_AGE_MS = 2L * 60L * 1000L;
    private static final long FUTURE_FIX_TOLERANCE_MS = 30L * 1000L;
    private static final float MAX_ACCURACY_METERS = 100.0f;
    private static final long STOP_CONFIRMATION_MS = 2L * 60L * 1000L;
    private static final long STATIONARY_HEARTBEAT_MS = 60L * 60L * 1000L;
    private static final long WALKING_INTERVAL_MS = 2L * 60L * 1000L;
    // APRS-IS guidance requires mobile beacon intervals of at least one minute.
    private static final long VEHICLE_FAST_INTERVAL_MS = 60L * 1000L;
    private static final long VEHICLE_MEDIUM_INTERVAL_MS = 90L * 1000L;
    private static final long VEHICLE_SLOW_INTERVAL_MS = 120L * 1000L;
    private static final long PTT_MIN_INTERVAL_MS = 2L * 60L * 1000L;
    private static final long PTT_SAME_POSITION_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long TURN_MIN_INTERVAL_MS = 60L * 1000L;
    private static final double TURN_MIN_DISTANCE_METERS = 30.0;
    private static final double TURN_MIN_DEGREES = 35.0;
    private static final double WALKING_SPEED_METERS_PER_SECOND = 0.7;
    private static final double VEHICLE_SPEED_METERS_PER_SECOND = 6.5;
    private static final double MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND = 100.0;

    private TrackingFix previousFix;
    private TrackingFix lastAcceptedFix;
    private long lastMeaningfulMovementElapsedRealtime;
    private MovementState movementState = MovementState.STATIONARY;
    private Beacon lastSuccessful;
    private long lastSuccessfulElapsedRealtime;
    private Beacon pending;
    private Beacon inFlight;
    private long lastPttQueuedElapsedRealtime;
    private long nextLogicalId = 1L;

    public synchronized Decision onLocation(TrackingFix fix, long nowWallTimeMillis,
                                            long nowElapsedRealtime) {
        String rejection = validateFix(fix, nowWallTimeMillis, nowElapsedRealtime);
        if (rejection != null) {
            return decision(false, false, rejection, null);
        }

        MovementState oldState = movementState;
        double distance = lastAcceptedFix == null ? 0.0 : distanceMeters(lastAcceptedFix, fix);
        long deltaTime = lastAcceptedFix == null ? 0L
                : Math.max(0L, fix.getElapsedRealtimeMillis()
                        - lastAcceptedFix.getElapsedRealtimeMillis());
        double jitterThreshold = lastAcceptedFix == null ? 0.0
                : Math.max(12.0, 0.75 * (lastAcceptedFix.getAccuracyMeters()
                        + fix.getAccuracyMeters()));
        boolean meaningfulMovement = lastAcceptedFix != null && distance > jitterThreshold;
        double speed = resolveSpeed(fix, distance, deltaTime, meaningfulMovement);

        if (meaningfulMovement) {
            lastMeaningfulMovementElapsedRealtime = nowElapsedRealtime;
            movementState = speed >= VEHICLE_SPEED_METERS_PER_SECOND
                    ? MovementState.VEHICLE : MovementState.WALKING;
        } else if (oldState != MovementState.STATIONARY
                && nowElapsedRealtime - lastMeaningfulMovementElapsedRealtime
                >= STOP_CONFIRMATION_MS) {
            movementState = MovementState.STATIONARY;
        }

        TriggerReason reason = chooseReason(fix, oldState, distance, speed,
                meaningfulMovement, nowElapsedRealtime);
        previousFix = lastAcceptedFix;
        lastAcceptedFix = fix;
        if (reason == null) {
            return decision(true, false, meaningfulMovement
                    ? "accepted-no-beacon" : "accepted-jitter-suppressed", null);
        }
        return queue(fix, movementState, reason, nowElapsedRealtime, true);
    }

    public synchronized Decision onPtt(long nowWallTimeMillis, long nowElapsedRealtime) {
        if (lastAcceptedFix == null) {
            return decision(false, false, "ptt-no-location", TriggerReason.PTT);
        }
        String rejection = validateFix(lastAcceptedFix, nowWallTimeMillis, nowElapsedRealtime);
        if (rejection != null) {
            return decision(false, false, "ptt-" + rejection, TriggerReason.PTT);
        }
        if (nowElapsedRealtime - lastPttQueuedElapsedRealtime < PTT_MIN_INTERVAL_MS) {
            return decision(true, false, "ptt-rate-limited", TriggerReason.PTT);
        }
        Decision decision = queue(lastAcceptedFix, movementState, TriggerReason.PTT,
                nowElapsedRealtime, true);
        if (decision.isBeaconQueued()) {
            lastPttQueuedElapsedRealtime = nowElapsedRealtime;
        }
        return decision;
    }

    public synchronized Beacon takeReady(long nowElapsedRealtime) {
        if (inFlight != null || pending == null
                || pending.getNextAttemptElapsedRealtime() > nowElapsedRealtime) {
            return null;
        }
        inFlight = pending;
        pending = null;
        return inFlight;
    }

    public synchronized boolean onSendSuccess(long logicalId, long nowElapsedRealtime) {
        if (inFlight == null || inFlight.getLogicalId() != logicalId) {
            return false;
        }
        lastSuccessful = inFlight;
        lastSuccessfulElapsedRealtime = nowElapsedRealtime;
        inFlight = null;
        return true;
    }

    public synchronized void onSendFailure(long logicalId, boolean uncertainDelivery,
                                           long nowElapsedRealtime) {
        if (inFlight == null || inFlight.getLogicalId() != logicalId) {
            return;
        }
        Beacon failed = inFlight;
        inFlight = null;
        if (pending != null && isMeaningfullyNewer(pending, failed)) {
            return;
        }
        long delay = retryDelayMillis(failed.getAttempt(), uncertainDelivery);
        pending = failed.retryAt(nowElapsedRealtime + delay);
    }

    public synchronized void onPermanentFailure(long logicalId) {
        if (inFlight != null && inFlight.getLogicalId() == logicalId) {
            inFlight = null;
        }
        if (pending != null && pending.getLogicalId() == logicalId) {
            pending = null;
        }
    }

    public synchronized void restoreLastSuccessful(TrackingFix fix, MovementState state,
                                                   long successfulElapsedRealtime) {
        if (fix == null || state == null) {
            return;
        }
        lastSuccessful = new Beacon(0L, fix, state, TriggerReason.HEARTBEAT,
                0, successfulElapsedRealtime);
        lastSuccessfulElapsedRealtime = successfulElapsedRealtime;
    }

    /** Drops duplicate state when the configured APRS Object identity changes. */
    public synchronized void resetForObjectIdentity() {
        lastSuccessful = null;
        lastSuccessfulElapsedRealtime = 0L;
        pending = null;
        inFlight = null;
        lastPttQueuedElapsedRealtime = 0L;
    }

    public synchronized TrackingFix getLastAcceptedFix() {
        return lastAcceptedFix;
    }

    public synchronized MovementState getMovementState() {
        return movementState;
    }

    public synchronized int getQueuedBeaconCount() {
        return (pending == null ? 0 : 1) + (inFlight == null ? 0 : 1);
    }

    public synchronized long getNextAttemptElapsedRealtime() {
        return pending == null ? -1L : pending.getNextAttemptElapsedRealtime();
    }

    private TriggerReason chooseReason(TrackingFix fix, MovementState oldState, double distance,
                                       double speed, boolean meaningfulMovement,
                                       long nowElapsedRealtime) {
        if (lastSuccessful == null) {
            return TriggerReason.FIRST_FIX;
        }
        if (oldState == MovementState.STATIONARY && movementState != MovementState.STATIONARY) {
            return TriggerReason.STARTED_MOVING;
        }
        if (oldState != MovementState.STATIONARY && movementState == MovementState.STATIONARY) {
            return TriggerReason.STOPPED;
        }

        long sinceSuccessful = nowElapsedRealtime - lastSuccessfulElapsedRealtime;
        double sinceSuccessfulDistance = distanceMeters(lastSuccessful.getFix(), fix);
        if (movementState != MovementState.STATIONARY && meaningfulMovement
                && sinceSuccessful >= TURN_MIN_INTERVAL_MS
                && sinceSuccessfulDistance >= TURN_MIN_DISTANCE_METERS
                && isSignificantTurn(fix)) {
            return TriggerReason.TURN;
        }

        long interval = beaconIntervalMillis(movementState, speed);
        if (sinceSuccessful >= interval) {
            return movementState == MovementState.STATIONARY
                    ? TriggerReason.HEARTBEAT : TriggerReason.MOVEMENT;
        }
        double distanceThreshold = movementState == MovementState.WALKING
                ? 120.0
                : movementState == MovementState.VEHICLE
                ? Math.max(120.0, speed * (interval / 1000.0) * 0.7)
                : Double.POSITIVE_INFINITY;
        if (meaningfulMovement && sinceSuccessfulDistance >= distanceThreshold) {
            return TriggerReason.MOVEMENT;
        }
        return null;
    }

    private boolean isSignificantTurn(TrackingFix fix) {
        if (previousFix == null || lastAcceptedFix == null) {
            return false;
        }
        double previousCourse = bearingDegrees(previousFix, lastAcceptedFix);
        double currentCourse = fix.hasBearing()
                ? fix.getBearingDegrees() : bearingDegrees(lastAcceptedFix, fix);
        double difference = Math.abs(previousCourse - currentCourse) % 360.0;
        difference = Math.min(difference, 360.0 - difference);
        return difference >= TURN_MIN_DEGREES;
    }

    private Decision queue(TrackingFix fix, MovementState state, TriggerReason reason,
                           long nowElapsedRealtime, boolean locationAccepted) {
        Beacon candidate = new Beacon(nextLogicalId, fix, state, reason, 0,
                nowElapsedRealtime);
        if (isDuplicateOf(candidate, inFlight, nowElapsedRealtime)
                || isDuplicateOf(candidate, pending, nowElapsedRealtime)
                || isDuplicateOfLastSuccess(candidate, nowElapsedRealtime)) {
            return decision(locationAccepted, false,
                    "duplicate-" + reason.name().toLowerCase(Locale.ROOT), reason);
        }
        nextLogicalId++;
        if (pending == null || isMeaningfullyNewer(candidate, pending)
                || isTransition(reason)) {
            pending = candidate;
            return decision(locationAccepted, true,
                    "queued-" + reason.name().toLowerCase(Locale.ROOT), reason);
        }
        return decision(locationAccepted, false, "older-than-pending", reason);
    }

    private boolean isDuplicateOfLastSuccess(Beacon candidate, long nowElapsedRealtime) {
        if (lastSuccessful == null || isTransition(candidate.getTriggerReason())
                || candidate.getTriggerReason() == TriggerReason.HEARTBEAT) {
            return false;
        }
        if (!isNear(candidate.getFix(), lastSuccessful.getFix())
                || candidate.getMovementState() != lastSuccessful.getMovementState()) {
            return false;
        }
        long elapsed = nowElapsedRealtime - lastSuccessfulElapsedRealtime;
        if (candidate.getTriggerReason() == TriggerReason.PTT) {
            return elapsed < PTT_SAME_POSITION_INTERVAL_MS;
        }
        return true;
    }

    private static boolean isDuplicateOf(Beacon candidate, Beacon existing,
                                         long nowElapsedRealtime) {
        if (existing == null || isTransition(candidate.getTriggerReason())) {
            return false;
        }
        return candidate.getMovementState() == existing.getMovementState()
                && isNear(candidate.getFix(), existing.getFix());
    }

    private static boolean isMeaningfullyNewer(Beacon candidate, Beacon existing) {
        return candidate.getFix().getElapsedRealtimeMillis()
                > existing.getFix().getElapsedRealtimeMillis()
                && (!isNear(candidate.getFix(), existing.getFix())
                || candidate.getMovementState() != existing.getMovementState()
                || isTransition(candidate.getTriggerReason()));
    }

    private static boolean isTransition(TriggerReason reason) {
        return reason == TriggerReason.STARTED_MOVING || reason == TriggerReason.STOPPED;
    }

    private Decision decision(boolean locationAccepted, boolean queued, String detail,
                              TriggerReason reason) {
        return new Decision(locationAccepted, queued, detail, movementState, reason);
    }

    private static String validateFix(TrackingFix fix, long nowWallTimeMillis,
                                      long nowElapsedRealtime) {
        if (fix == null || !fix.hasValidCoordinates()) {
            return "invalid-location";
        }
        if (!Float.isFinite(fix.getAccuracyMeters()) || fix.getAccuracyMeters() <= 0.0f
                || fix.getAccuracyMeters() > MAX_ACCURACY_METERS) {
            return "poor-accuracy";
        }
        long elapsedAge = fix.getElapsedRealtimeMillis() > 0L
                ? nowElapsedRealtime - fix.getElapsedRealtimeMillis()
                : nowWallTimeMillis - fix.getWallTimeMillis();
        if (elapsedAge > MAX_FIX_AGE_MS) {
            return "stale-location";
        }
        if (elapsedAge < -FUTURE_FIX_TOLERANCE_MS) {
            return "future-location";
        }
        return null;
    }

    private static double resolveSpeed(TrackingFix fix, double distance, long deltaTime,
                                       boolean meaningfulMovement) {
        if (!meaningfulMovement) {
            return 0.0;
        }
        if (fix.hasSpeed() && fix.getSpeedMetersPerSecond()
                <= MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND) {
            return fix.getSpeedMetersPerSecond();
        }
        if (deltaTime < 1000L) {
            return 0.0;
        }
        return Math.min(MAX_PLAUSIBLE_SPEED_METERS_PER_SECOND,
                distance / (deltaTime / 1000.0));
    }

    private static long beaconIntervalMillis(MovementState state, double speed) {
        if (state == MovementState.STATIONARY) {
            return STATIONARY_HEARTBEAT_MS;
        }
        if (state == MovementState.WALKING) {
            return WALKING_INTERVAL_MS;
        }
        if (speed >= 27.0) {
            return VEHICLE_FAST_INTERVAL_MS;
        }
        if (speed >= 13.0) {
            return VEHICLE_MEDIUM_INTERVAL_MS;
        }
        return VEHICLE_SLOW_INTERVAL_MS;
    }

    private static long retryDelayMillis(int attempt, boolean uncertainDelivery) {
        if (uncertainDelivery) {
            return Math.min(60L * 60L * 1000L,
                    15L * 60L * 1000L * (1L << Math.min(2, attempt)));
        }
        long[] delays = {60L * 1000L, 5L * 60L * 1000L,
                15L * 60L * 1000L, 60L * 60L * 1000L};
        return delays[Math.min(attempt, delays.length - 1)];
    }

    private static boolean isNear(TrackingFix first, TrackingFix second) {
        double threshold = Math.max(15.0,
                Math.min(75.0, Math.max(first.getAccuracyMeters(), second.getAccuracyMeters())));
        return distanceMeters(first, second) <= threshold;
    }

    static double distanceMeters(TrackingFix first, TrackingFix second) {
        double latitude1 = Math.toRadians(first.getLatitude());
        double latitude2 = Math.toRadians(second.getLatitude());
        double latitudeDelta = latitude2 - latitude1;
        double longitudeDelta = Math.toRadians(second.getLongitude() - first.getLongitude());
        double a = Math.sin(latitudeDelta / 2.0) * Math.sin(latitudeDelta / 2.0)
                + Math.cos(latitude1) * Math.cos(latitude2)
                * Math.sin(longitudeDelta / 2.0) * Math.sin(longitudeDelta / 2.0);
        return 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static double bearingDegrees(TrackingFix first, TrackingFix second) {
        double latitude1 = Math.toRadians(first.getLatitude());
        double latitude2 = Math.toRadians(second.getLatitude());
        double longitudeDelta = Math.toRadians(second.getLongitude() - first.getLongitude());
        double y = Math.sin(longitudeDelta) * Math.cos(latitude2);
        double x = Math.cos(latitude1) * Math.sin(latitude2)
                - Math.sin(latitude1) * Math.cos(latitude2) * Math.cos(longitudeDelta);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
