/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

/** Immutable location sample used by the platform-independent tracking pipeline. */
public final class TrackingFix {
    public static final float UNKNOWN = -1.0f;

    private final double latitude;
    private final double longitude;
    private final float accuracyMeters;
    private final long wallTimeMillis;
    private final long elapsedRealtimeMillis;
    private final float speedMetersPerSecond;
    private final float bearingDegrees;

    public TrackingFix(double latitude, double longitude, float accuracyMeters,
                       long wallTimeMillis, long elapsedRealtimeMillis,
                       float speedMetersPerSecond, float bearingDegrees) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.wallTimeMillis = wallTimeMillis;
        this.elapsedRealtimeMillis = elapsedRealtimeMillis;
        this.speedMetersPerSecond = speedMetersPerSecond;
        this.bearingDegrees = bearingDegrees;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public float getAccuracyMeters() {
        return accuracyMeters;
    }

    public long getWallTimeMillis() {
        return wallTimeMillis;
    }

    public long getElapsedRealtimeMillis() {
        return elapsedRealtimeMillis;
    }

    public float getSpeedMetersPerSecond() {
        return speedMetersPerSecond;
    }

    public float getBearingDegrees() {
        return bearingDegrees;
    }

    public boolean hasSpeed() {
        return Float.isFinite(speedMetersPerSecond) && speedMetersPerSecond >= 0.0f;
    }

    public boolean hasBearing() {
        return Float.isFinite(bearingDegrees) && bearingDegrees >= 0.0f
                && bearingDegrees <= 360.0f;
    }

    public boolean hasValidCoordinates() {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }
}
