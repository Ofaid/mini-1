/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/** Encodes conventional uncompressed APRS position and Object reports for APRS-IS. */
public final class AprsPacketEncoder {
    private static final double METERS_PER_SECOND_TO_KNOTS = 1.9438444924406;

    private AprsPacketEncoder() {
    }

    /** Encodes a timestamped position report for a configured APRS source. */
    public static String encodePosition(String sourceCallsign, TrackingFix fix,
                                        char symbolTable, char symbolCode, String comment) {
        String source = normalizeCallsign(sourceCallsign);
        validatePosition(fix, symbolTable, symbolCode);

        StringBuilder packet = new StringBuilder(80);
        packet.append(source).append(">APRS,TCPIP*:@")
                .append(formatTimestamp(fix.getWallTimeMillis()))
                .append(formatLatitude(fix.getLatitude()))
                .append(symbolTable)
                .append(formatLongitude(fix.getLongitude()))
                .append(symbolCode);
        appendMovementAndComment(packet, fix, comment);
        return packet.toString();
    }

    public static String encodeObject(String sourceCallsign, String objectName,
                                      TrackingFix fix, char symbolTable, char symbolCode,
                                      String comment) {
        String source = normalizeCallsign(sourceCallsign);
        if (objectName == null || objectName.length() != AprsObjectName.APRS_OBJECT_NAME_LENGTH
                || objectName.indexOf(';') >= 0 || objectName.indexOf('\r') >= 0
                || objectName.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("APRS Object name must be exactly nine characters");
        }
        validatePosition(fix, symbolTable, symbolCode);

        StringBuilder packet = new StringBuilder(96);
        packet.append(source).append(">APRS,TCPIP*:;")
                .append(objectName).append('*')
                .append(formatTimestamp(fix.getWallTimeMillis()))
                .append(formatLatitude(fix.getLatitude()))
                .append(symbolTable)
                .append(formatLongitude(fix.getLongitude()))
                .append(symbolCode);
        appendMovementAndComment(packet, fix, comment);
        return packet.toString();
    }

    private static void appendMovementAndComment(StringBuilder packet, TrackingFix fix,
                                                 String comment) {
        if (fix.hasSpeed() && fix.hasBearing() && fix.getSpeedMetersPerSecond() >= 0.5f) {
            int course = Math.round(fix.getBearingDegrees()) % 360;
            if (course == 0) {
                course = 360;
            }
            int knots = Math.min(999, Math.max(0,
                    (int) Math.round(fix.getSpeedMetersPerSecond()
                            * METERS_PER_SECOND_TO_KNOTS)));
            packet.append(String.format(Locale.ROOT, "%03d/%03d", course, knots));
        }
        String safeComment = sanitizeComment(comment);
        if (!safeComment.isEmpty()) {
            packet.append(safeComment);
        }
    }

    private static void validatePosition(TrackingFix fix, char symbolTable, char symbolCode) {
        if (fix == null || !fix.hasValidCoordinates()) {
            throw new IllegalArgumentException("invalid APRS position");
        }
        if (symbolTable < 0x21 || symbolTable > 0x7e
                || symbolCode < 0x21 || symbolCode > 0x7e) {
            throw new IllegalArgumentException("invalid APRS symbol");
        }
    }

    static String formatTimestamp(long wallTimeMillis) {
        Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        utc.setTimeInMillis(wallTimeMillis);
        return String.format(Locale.ROOT, "%02d%02d%02dz",
                utc.get(Calendar.DAY_OF_MONTH), utc.get(Calendar.HOUR_OF_DAY),
                utc.get(Calendar.MINUTE));
    }

    static String formatLatitude(double latitude) {
        return formatCoordinate(latitude, 90, 2, latitude < 0.0 ? 'S' : 'N');
    }

    static String formatLongitude(double longitude) {
        return formatCoordinate(longitude, 180, 3, longitude < 0.0 ? 'W' : 'E');
    }

    private static String formatCoordinate(double coordinate, int maximumDegrees,
                                           int degreeDigits, char hemisphere) {
        double absolute = Math.abs(coordinate);
        int degrees = (int) Math.floor(absolute);
        double minutes = (absolute - degrees) * 60.0;
        minutes = Math.round(minutes * 100.0) / 100.0;
        if (minutes >= 60.0) {
            minutes = 0.0;
            degrees++;
        }
        if (degrees > maximumDegrees) {
            throw new IllegalArgumentException("coordinate out of range");
        }
        return String.format(Locale.ROOT, "%0" + degreeDigits + "d%05.2f%c",
                degrees, minutes, hemisphere);
    }

    private static String normalizeCallsign(String value) {
        String callsign = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!callsign.matches("[A-Z0-9]{3,6}(-[A-Z0-9]{1,2})?")
                || callsign.endsWith("-0")) {
            throw new IllegalArgumentException("invalid APRS source callsign");
        }
        return callsign;
    }

    private static String sanitizeComment(String comment) {
        if (comment == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(Math.min(40, comment.length()));
        for (int index = 0; index < comment.length() && result.length() < 40; index++) {
            char character = comment.charAt(index);
            if (character >= 0x20 && character <= 0x7e && character != '|'
                    && character != '~') {
                result.append(character);
            }
        }
        return result.toString().trim();
    }
}
