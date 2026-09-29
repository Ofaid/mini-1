/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Creates and persists the public six-character identity used by the radio PoC.
 *
 * The value is deliberately unrelated to hardware identifiers. It survives app updates and
 * reboots, while clearing app data causes the normal first-run path to create a new value.
 */
public final class DeviceIdentityManager {
    public static final int DEVICE_ID_LENGTH = 6;
    public static final String DEVICE_ID_PREFERENCE = "radio_device_id";

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String ALPHANUMERIC = LETTERS + DIGITS;

    private final SharedPreferences preferences;
    private final SecureRandom secureRandom;

    public DeviceIdentityManager(SharedPreferences preferences) {
        this(preferences, new SecureRandom());
    }

    DeviceIdentityManager(SharedPreferences preferences, SecureRandom secureRandom) {
        if (preferences == null) {
            throw new IllegalArgumentException("preferences must not be null");
        }
        if (secureRandom == null) {
            throw new IllegalArgumentException("secureRandom must not be null");
        }
        this.preferences = preferences;
        this.secureRandom = secureRandom;
    }

    /** Returns the existing identity or creates it once on first use. */
    public String getOrCreateDeviceId() {
        String existing = preferences.getString(DEVICE_ID_PREFERENCE, null);
        if (isValidDeviceId(existing)) {
            return existing;
        }

        String generated = generateDeviceId();
        preferences.edit().putString(DEVICE_ID_PREFERENCE, generated).apply();
        return generated;
    }

    /** Returns the existing identity without creating or modifying app state. */
    public String getExistingDeviceId() {
        String existing = preferences.getString(DEVICE_ID_PREFERENCE, null);
        return isValidDeviceId(existing) ? existing : null;
    }

    /**
     * Generates a new identity for an explicitly authorized administrative action.
     * Callers must protect the UI/action that invokes this method.
     */
    public String regenerateDeviceIdForAdmin() {
        String generated = generateDeviceId();
        preferences.edit().putString(DEVICE_ID_PREFERENCE, generated).apply();
        return generated;
    }

    /** Assigns an externally managed lookup identity through an authorized provisioning path. */
    public void setDeviceIdForAdmin(String deviceId) {
        if (!isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("invalid device id");
        }
        if (!preferences.edit().putString(DEVICE_ID_PREFERENCE, deviceId).commit()) {
            throw new IllegalStateException("device id could not be persisted");
        }
    }

    public static boolean isValidDeviceId(String value) {
        if (value == null || value.length() != DEVICE_ID_LENGTH) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (LETTERS.indexOf(character) >= 0) {
                hasLetter = true;
            } else if (DIGITS.indexOf(character) >= 0) {
                hasDigit = true;
            } else {
                return false;
            }
        }
        return hasLetter && hasDigit;
    }

    private String generateDeviceId() {
        char[] characters = new char[DEVICE_ID_LENGTH];
        characters[0] = LETTERS.charAt(secureRandom.nextInt(LETTERS.length()));
        characters[1] = DIGITS.charAt(secureRandom.nextInt(DIGITS.length()));
        for (int i = 2; i < characters.length; i++) {
            characters[i] = ALPHANUMERIC.charAt(secureRandom.nextInt(ALPHANUMERIC.length()));
        }

        // Fisher-Yates shuffle prevents the required letter/digit from having a fixed position.
        for (int i = characters.length - 1; i > 0; i--) {
            int swapIndex = secureRandom.nextInt(i + 1);
            char temporary = characters[i];
            characters[i] = characters[swapIndex];
            characters[swapIndex] = temporary;
        }
        return new String(characters);
    }
}
