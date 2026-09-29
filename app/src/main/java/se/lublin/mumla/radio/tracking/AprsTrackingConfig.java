/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

import se.lublin.mumla.radio.RadioDeviceProfile;

/** Minimal validated configuration for T56-only APRS tracking. */
public final class AprsTrackingConfig {
    public static final String DEFAULT_HOST = "ametx.com";
    public static final int DEFAULT_PORT = 8888;

    private final boolean enabled;
    private final boolean aprsEnabled;
    private final boolean pttTriggered;
    private final String sourceCallsign;
    private final String passcode;
    private final String host;
    private final int port;
    private final String objectName;

    private AprsTrackingConfig(boolean enabled, boolean aprsEnabled, boolean pttTriggered,
                               String sourceCallsign, String passcode, String host, int port,
                               String objectName) {
        this.enabled = enabled;
        this.aprsEnabled = aprsEnabled;
        this.pttTriggered = pttTriggered;
        this.sourceCallsign = sourceCallsign;
        this.passcode = passcode;
        this.host = host;
        this.port = port;
        this.objectName = objectName;
    }

    public static AprsTrackingConfig disabled() {
        return new AprsTrackingConfig(false, false, false, "", "", DEFAULT_HOST, DEFAULT_PORT,
                "");
    }

    public static AprsTrackingConfig fromJson(JSONObject root, String hardwareProfile)
            throws JSONException {
        if (!RadioDeviceProfile.supportsLocationTracking(hardwareProfile)) {
            return disabled();
        }
        JSONObject tracking = root == null ? null : root.optJSONObject("tracking");
        if (tracking == null || !tracking.optBoolean("enabled", false)) {
            return disabled();
        }
        JSONObject aprs = tracking.optJSONObject("aprs");
        boolean aprsEnabled = aprs != null && aprs.optBoolean("enabled", false);
        if (!aprsEnabled) {
            return new AprsTrackingConfig(true, false,
                    tracking.optBoolean("pttTriggered", true), "", "",
                    DEFAULT_HOST, DEFAULT_PORT, "");
        }

        String callsign = requireString(aprs, "sourceCallsign").toUpperCase(Locale.ROOT);
        if (!callsign.matches("[A-Z0-9]{3,6}(-[A-Z0-9]{1,2})?")
                || callsign.endsWith("-0")) {
            throw new JSONException("invalid APRS source callsign");
        }
        String passcode = requireString(aprs, "passcode");
        if (!passcode.matches("[0-9]{1,5}")) {
            throw new JSONException("invalid APRS passcode");
        }
        int passcodeNumber;
        try {
            passcodeNumber = Integer.parseInt(passcode);
        } catch (NumberFormatException exception) {
            throw new JSONException("invalid APRS passcode");
        }
        if (passcodeNumber < 0 || passcodeNumber > 32767) {
            throw new JSONException("invalid APRS passcode");
        }
        String host = aprs.optString("host", DEFAULT_HOST).trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.length() > 253 || !host.matches("[a-z0-9.-]+")) {
            throw new JSONException("invalid APRS-IS host");
        }
        int port = aprs.optInt("port", DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            throw new JSONException("invalid APRS-IS port");
        }
        String objectName = "";
        if (aprs.has("objectName")) {
            if (!(aprs.opt("objectName") instanceof String)) {
                throw new JSONException("invalid APRS Object name");
            }
            try {
                objectName = AprsObjectName.fromConfiguredName(aprs.optString("objectName"));
            } catch (IllegalArgumentException exception) {
                throw new JSONException(exception.getMessage());
            }
        }
        return new AprsTrackingConfig(true, true,
                tracking.optBoolean("pttTriggered", true), callsign, passcode, host, port,
                objectName);
    }

    private static String requireString(JSONObject object, String key) throws JSONException {
        if (object == null || !(object.opt(key) instanceof String)) {
            throw new JSONException("missing " + key);
        }
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            throw new JSONException("empty " + key);
        }
        return value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAprsEnabled() {
        return aprsEnabled;
    }

    public boolean isPttTriggered() {
        return pttTriggered;
    }

    public String getSourceCallsign() {
        return sourceCallsign;
    }

    public String getPasscode() {
        return passcode;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** Returns the normalized nine-character name, or an empty string for Device ID fallback. */
    public String getObjectName() {
        return objectName;
    }
}
