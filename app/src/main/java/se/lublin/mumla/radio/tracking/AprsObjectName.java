/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Maps a public Minimum Device ID or configured label to APRS' fixed nine-character Object name. */
public final class AprsObjectName {
    public static final int APRS_OBJECT_NAME_LENGTH = 9;
    private static final String OBJECT_PREFIX = "VR-";
    private static final char[] BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private AprsObjectName() {
    }

    public static String fromDeviceId(String deviceId) {
        String normalized = deviceId == null ? "" : deviceId.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("[A-Z0-9]{1,6}")) {
            return pad(OBJECT_PREFIX + normalized);
        }

        // Defensive fallback for a future identity format: retain a recognizable prefix and add
        // a stable four-character digest. Current six-character Device IDs take the direct path.
        String readable = normalized.replaceAll("[^A-Z0-9]", "");
        if (readable.isEmpty()) {
            readable = "MIN";
        }
        readable = readable.substring(0, Math.min(3, readable.length()));
        return pad(OBJECT_PREFIX + readable + digestBase36(normalized).substring(0, 3));
    }

    /**
     * Normalizes an optional configured APRS Object label. APRS Object names are nine bytes on
     * the wire, so shorter labels are right-padded with spaces after validation.
     */
    public static String fromConfiguredName(String configuredName) {
        return pad(normalizeConfiguredName(configuredName));
    }

    /** Returns the validated unpadded label suitable for storing in JSON configuration. */
    public static String normalizeConfiguredName(String configuredName) {
        if (configuredName == null) {
            throw new IllegalArgumentException("APRS Object name is missing");
        }
        String normalized = configuredName.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9](?:[A-Z0-9 _-]{0,7}[A-Z0-9_-])?")) {
            throw new IllegalArgumentException("invalid APRS Object name");
        }
        return normalized;
    }

    private static String pad(String value) {
        StringBuilder result = new StringBuilder(value.substring(0,
                Math.min(APRS_OBJECT_NAME_LENGTH, value.length())));
        while (result.length() < APRS_OBJECT_NAME_LENGTH) {
            result.append(' ');
        }
        return result.toString();
    }

    private static String digestBase36(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long number = ((long) (digest[0] & 0xff) << 24)
                    | ((long) (digest[1] & 0xff) << 16)
                    | ((long) (digest[2] & 0xff) << 8)
                    | (digest[3] & 0xffL);
            char[] result = new char[4];
            for (int index = result.length - 1; index >= 0; index--) {
                result[index] = BASE36[(int) (number % BASE36.length)];
                number /= BASE36.length;
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
