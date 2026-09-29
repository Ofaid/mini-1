/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import se.lublin.mumla.radio.tracking.AprsObjectName;

/**
 * Loads the radio configuration from the embedded safe default and managed control plane.
 * Network refresh must be called from a worker thread. Registered radios use their stable
 * six-character Device ID as the lookup key and do not require a second provisioning secret.
 */
public final class RadioConfigRepository {
    public static final int SCHEMA_VERSION = 3;
    public static final int MAX_CONFIG_BYTES = 262144;
    public static final int MAX_PTT_SECONDS = 120;
    public static final String DEVICE_CONFIG_BASE_URL =
            "https://minimum.vra.or.th/api/device-config/";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final String ASSET_DEFAULT = "radio/default.json";
    private static final String ACTIVE_FILE = "active-config.json";
    private static final String PREVIOUS_FILE = "previous-config.json";
    private static final String PENDING_FILE = "pending-config.json";
    private static final String TEMP_FILE = "downloaded-config.tmp";
    private static final String PROVISIONED_TEMP_FILE = "provisioned-config.tmp";
    private static final String ROLLBACK_TEMP_FILE = "rollback-config.tmp";
    private static final String PREVIOUS_BACKUP_FILE = "previous-config.backup";
    private static final Object CACHE_LOCK = new Object();

    private final Context context;
    private volatile SSLSocketFactory configSslSocketFactory;

    public RadioConfigRepository(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
    }

    /** Returns the validated active cache, or the embedded default if the cache is absent/bad. */
    public JSONObject loadActiveOrDefault() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File directory = cacheDirectory();
            File active = new File(directory, ACTIVE_FILE);
            if (active.isFile()) {
                try {
                    JSONObject cached = readJson(active);
                    validateCompleteConfig(cached, null);
                    return cached;
                } catch (JSONException | IOException ignored) {
                    // Try the previous Last Known Good before the embedded fallback.
                }
            }
            File previous = new File(directory, PREVIOUS_FILE);
            if (previous.isFile()) {
                try {
                    JSONObject recovered = readJson(previous);
                    validateCompleteConfig(recovered, null);
                    rollbackFiles(directory);
                    return recovered;
                } catch (JSONException | IOException ignored) {
                    // A bad cache must never prevent the radio client from starting.
                }
            }
        }
        JSONObject fallback = readEmbeddedDefault();
        validateCompleteConfig(fallback, null);
        return fallback;
    }

    /** Reads the active Last Known Good cache without fallback, rollback, creation, or mutation. */
    public JSONObject loadActiveForReport() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File active = new File(cacheDirectoryForRead(), ACTIVE_FILE);
            if (!active.isFile()) {
                throw new IOException("active radio config is missing");
            }
            JSONObject cached = readJson(active);
            validateCompleteConfig(cached, null);
            return cached;
        }
    }

    /**
     * Fetches and merges default, model and optional device configuration. The result is staged as
     * pending and cannot replace the Last Known Good active config until the radio proves it works.
     */
    public JSONObject refresh(String deviceId, String modelProfile)
            throws IOException, JSONException {
        if (!DeviceIdentityManager.isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("invalid device id");
        }
        if (!isSafePathPart(modelProfile)) {
            throw new IllegalArgumentException("invalid model profile");
        }
        JSONObject merged = fetchDeviceConfig(deviceId);
        validateCompleteConfig(merged, deviceId);
        synchronized (CACHE_LOCK) {
            File active = new File(cacheDirectory(), ACTIVE_FILE);
            if (active.isFile()) {
                JSONObject current = null;
                try {
                    current = readJson(active);
                    validateCompleteConfig(current, null);
                } catch (IOException | JSONException ignored) {
                    // An unreadable active cache is not a valid equality baseline.
                }
                if (current != null) {
                    rejectDowngrade(merged, current);
                    if (current.optInt("configVersion", -1)
                            == merged.optInt("configVersion", -2)) {
                        if (!current.toString().equals(merged.toString())) {
                            throw new JSONException(
                                    "config content changed without version advance");
                        }
                        discardPendingLocked();
                        return merged;
                    }
                }
            }
            writePendingLocked(merged);
        }
        return merged;
    }

    /** Returns the validated candidate waiting for an idle radio trial, or null when absent. */
    public JSONObject loadPending() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File pending = new File(cacheDirectory(), PENDING_FILE);
            if (!pending.isFile()) {
                return null;
            }
            JSONObject candidate = readJson(pending);
            validateCompleteConfig(candidate, null);
            return candidate;
        }
    }

    public boolean hasPending() {
        synchronized (CACHE_LOCK) {
            return new File(cacheDirectory(), PENDING_FILE).isFile();
        }
    }

    /** Commits a candidate only after the radio connected and joined its configured room. */
    public JSONObject commitPending() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File directory = cacheDirectory();
            File pending = new File(directory, PENDING_FILE);
            if (!pending.isFile()) {
                throw new IOException("no pending config to commit");
            }
            JSONObject candidate = readJson(pending);
            validateCompleteConfig(candidate, null);
            File active = new File(directory, ACTIVE_FILE);
            if (active.isFile()) {
                try {
                    JSONObject current = readJson(active);
                    validateCompleteConfig(current, null);
                    rejectDowngrade(candidate, current);
                } catch (JSONException error) {
                    throw error;
                } catch (IOException ignored) {
                    // An unreadable active cache is not a valid downgrade baseline.
                }
            }
            promotePendingFiles(directory);
            return candidate;
        }
    }

    /** Explicitly restores the previous Last Known Good config and returns it. */
    public JSONObject rollbackToPrevious() throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File directory = cacheDirectory();
            File previous = new File(directory, PREVIOUS_FILE);
            if (!previous.isFile()) {
                throw new IOException("no previous config to roll back");
            }
            JSONObject restored = readJson(previous);
            validateCompleteConfig(restored, null);
            rollbackFiles(directory);
            return restored;
        }
    }

    public void discardPending() throws IOException {
        synchronized (CACHE_LOCK) {
            discardPendingLocked();
        }
    }

    /** Installs an explicitly provisioned Last Known Good config from the protected ADB path. */
    public JSONObject installProvisionedActive(InputStream input, String expectedDeviceId)
            throws IOException, JSONException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        JSONObject provisioned = new JSONObject(readLimited(input));
        validateCompleteConfig(provisioned, expectedDeviceId);
        synchronized (CACHE_LOCK) {
            File directory = cacheDirectory();
            File active = new File(directory, ACTIVE_FILE);
            if (active.isFile()) {
                JSONObject current = readJson(active);
                validateCompleteConfig(current, null);
                rejectDowngrade(provisioned, current);
                if (current.optInt("configVersion", -1)
                        == provisioned.optInt("configVersion", -2)
                        && !current.toString().equals(provisioned.toString())) {
                    throw new JSONException("config content changed without version advance");
                }
            }
            installProvisionedFiles(directory,
                    provisioned.toString().getBytes(StandardCharsets.UTF_8));
        }
        return provisioned;
    }

    /** Updates only the active APRS Object label without exporting the private config. */
    public JSONObject updateActiveAprsObjectName(String expectedDeviceId, String configuredName)
            throws IOException, JSONException {
        synchronized (CACHE_LOCK) {
            File directory = cacheDirectory();
            File active = new File(directory, ACTIVE_FILE);
            if (!active.isFile()) {
                throw new IOException("active radio config is missing");
            }
            JSONObject current = readJson(active);
            JSONObject updated = withAprsObjectName(current, expectedDeviceId, configuredName);
            if (!current.toString().equals(updated.toString())) {
                installProvisionedFiles(directory,
                        updated.toString().getBytes(StandardCharsets.UTF_8));
            }
            return updated;
        }
    }

    static JSONObject withAprsObjectName(JSONObject current, String expectedDeviceId,
                                         String configuredName) throws JSONException {
        validateConfig(current, expectedDeviceId);
        String normalized;
        try {
            normalized = AprsObjectName.normalizeConfiguredName(configuredName);
        } catch (IllegalArgumentException exception) {
            throw new JSONException(exception.getMessage());
        }
        JSONObject updated = new JSONObject(current.toString());
        JSONObject tracking = updated.optJSONObject("tracking");
        JSONObject aprs = tracking == null ? null : tracking.optJSONObject("aprs");
        if (aprs == null) {
            throw new JSONException("APRS config is missing");
        }
        if (normalized.equals(aprs.optString("objectName", ""))) {
            return updated;
        }
        int version = updated.optInt("configVersion", 0);
        if (version < 1 || version == Integer.MAX_VALUE) {
            throw new JSONException("config version cannot be advanced");
        }
        aprs.put("objectName", normalized);
        updated.put("configVersion", version + 1);
        validateConfig(updated, expectedDeviceId);
        return updated;
    }

    /** Rejects a candidate that would replace a newer Last Known Good configuration. */
    public static void rejectDowngrade(JSONObject candidate, JSONObject active)
            throws JSONException {
        int candidateVersion = candidate == null ? -1 : candidate.optInt("configVersion", -1);
        int activeVersion = active == null ? -1 : active.optInt("configVersion", -1);
        if (candidateVersion < activeVersion) {
            throw new JSONException("config downgrade rejected");
        }
    }

    /** Deep-merges JSON objects; arrays and scalar values from overlay replace the base value. */
    public static JSONObject merge(JSONObject base, JSONObject overlay) throws JSONException {
        JSONObject result = new JSONObject(base.toString());
        JSONArray names = overlay.names();
        if (names == null) {
            return result;
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.getString(i);
            Object overlayValue = overlay.get(name);
            Object baseValue = result.opt(name);
            if (overlayValue instanceof JSONObject && baseValue instanceof JSONObject) {
                result.put(name, merge((JSONObject) baseValue, (JSONObject) overlayValue));
            } else {
                result.put(name, overlayValue);
            }
        }
        return result;
    }

    /** Validates invariants that protect startup and the PTT safety contract. */
    public static void validateConfig(JSONObject config, String expectedDeviceId)
            throws JSONException {
        validateOverlay(config, expectedDeviceId);
        JSONObject radio = config.optJSONObject("radio");
        JSONObject ptt = config.optJSONObject("ptt");
        if (radio == null || radio.optString("defaultChannel", "").isEmpty()) {
            throw new JSONException("incomplete radio selection config");
        }
        if (ptt == null || ptt.optInt("maximumTxSeconds", 0) < 1
                || ptt.optInt("maximumTxSeconds", MAX_PTT_SECONDS) > MAX_PTT_SECONDS
                || !ptt.optBoolean("releaseOnNetworkLoss", false)) {
            throw new JSONException("unsafe PTT config");
        }
        JSONObject connections = config.optJSONObject("connections");
        JSONArray channels = config.optJSONArray("channels");
        JSONObject hardware = config.optJSONObject("hardware");
        if (connections == null || connections.length() == 0
                || channels == null || channels.length() == 0 || hardware == null
                || hardware.optString("profile", "").isEmpty()) {
            throw new JSONException("incomplete radio config");
        }
    }

    /**
     * Validates repository invariants and the complete live radio shape before persistence.
     * Overlay validation intentionally omits fields supplied by a base config; this additionally
     * runs the parser's channel, connection, access-policy and path checks.
     */
    static void validateCompleteConfig(JSONObject config, String expectedDeviceId)
            throws JSONException {
        validateConfig(config, expectedDeviceId);
        RadioConnectionConfig.fromJson(config);
    }

    /** Validates a complete config overlay without requiring fields supplied by the base config. */
    public static void validateOverlay(JSONObject config, String expectedDeviceId)
            throws JSONException {
        if (config == null || config.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            throw new JSONException("unsupported config schema");
        }
        if (config.optInt("configVersion", 0) < 1) {
            throw new JSONException("invalid config version");
        }
        String configDeviceId = config.optString("deviceId", "*");
        if (!"*".equals(configDeviceId)
                && !DeviceIdentityManager.isValidDeviceId(configDeviceId)) {
            throw new JSONException("invalid config device id");
        }
        if (expectedDeviceId != null && !"*".equals(configDeviceId)
                && !expectedDeviceId.equals(configDeviceId)) {
            throw new JSONException("config is for another device");
        }
        JSONObject connections = config.optJSONObject("connections");
        if (connections != null) {
            java.util.Iterator<String> ids = connections.keys();
            while (ids.hasNext()) {
                JSONObject connection = connections.optJSONObject(ids.next());
                if (connection == null) {
                    throw new JSONException("invalid connection overlay");
                }
                if (connection.has("username")) {
                    if (!(connection.opt("username") instanceof String)) {
                        throw new JSONException("invalid Mumble username type");
                    }
                    RadioConnectionConfig.normalizeMumbleUsername(
                            connection.optString("username", ""));
                }
                if (connection.has("port")) {
                    int port = connection.optInt("port", -1);
                    if (port < 1 || port > 65535) {
                        throw new JSONException("invalid Mumble port");
                    }
                }
                if (connection.has("serverCertificateSha256")) {
                    RadioConnectionConfig.normalizeFingerprint(
                            connection.optString("serverCertificateSha256", ""));
                }
                if (connection.has("autoTrustServerCertificate")
                        && !(connection.opt("autoTrustServerCertificate") instanceof Boolean)) {
                    throw new JSONException("invalid automatic certificate trust policy");
                }
                if (connection.has("password")
                        && !(connection.opt("password") instanceof String)) {
                    throw new JSONException("invalid server password type");
                }
            }
        }
        JSONObject radio = config.optJSONObject("radio");
        if (radio != null) {
            if (radio.has("autoConnect") && !(radio.opt("autoConnect") instanceof Boolean)) {
                throw new JSONException("invalid automatic connection policy");
            }
            if (radio.has("autoReconnect")
                    && !(radio.opt("autoReconnect") instanceof Boolean)) {
                throw new JSONException("invalid automatic reconnection policy");
            }
        }
        JSONObject ptt = config.optJSONObject("ptt");
        if (ptt != null) {
            int maximum = ptt.optInt("maximumTxSeconds", MAX_PTT_SECONDS);
            if (maximum < 1 || maximum > MAX_PTT_SECONDS
                    || (ptt.has("releaseOnNetworkLoss")
                    && !ptt.optBoolean("releaseOnNetworkLoss", false))) {
                throw new JSONException("unsafe PTT overlay");
            }
        }
        JSONArray channels = config.optJSONArray("channels");
        if (channels != null && channels.length() == 0) {
            throw new JSONException("empty channel overlay");
        }
        JSONObject hardware = config.optJSONObject("hardware");
        if (hardware != null && hardware.has("profile")
                && hardware.optString("profile", "").isEmpty()) {
            throw new JSONException("empty hardware profile");
        }
        if (config.has("tracking")) {
            JSONObject tracking = config.optJSONObject("tracking");
            if (tracking == null) {
                throw new JSONException("invalid tracking overlay");
            }
            if (tracking.has("aprs")) {
                JSONObject aprs = tracking.optJSONObject("aprs");
                if (aprs == null) {
                    throw new JSONException("invalid APRS overlay");
                }
                if (aprs.has("objectName")) {
                    if (!(aprs.opt("objectName") instanceof String)) {
                        throw new JSONException("invalid APRS Object name type");
                    }
                    try {
                        AprsObjectName.normalizeConfiguredName(
                                aprs.optString("objectName", ""));
                    } catch (IllegalArgumentException exception) {
                        throw new JSONException(exception.getMessage());
                    }
                }
            }
        }
    }

    private JSONObject readEmbeddedDefault() throws IOException, JSONException {
        try (InputStream input = context.getAssets().open(ASSET_DEFAULT)) {
            return new JSONObject(readLimited(input));
        }
    }

    private JSONObject fetchDeviceConfig(String deviceId)
            throws IOException, JSONException {
        HttpURLConnection connection = openConnection(new URL(deviceConfigUrl(deviceId)));
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new DeviceConfigUnavailableException();
            }
            if (status < 200 || status >= 300) {
                throw new IOException("device config request failed");
            }
            return new JSONObject(readLimited(connection.getInputStream()));
        } finally {
            connection.disconnect();
        }
    }

    static String deviceConfigUrl(String deviceId) {
        if (!DeviceIdentityManager.isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("invalid device id");
        }
        return DEVICE_CONFIG_BASE_URL + deviceId;
    }

    private HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection) {
            ((HttpsURLConnection) connection).setSSLSocketFactory(configSslSocketFactory());
        }
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private SSLSocketFactory configSslSocketFactory() throws IOException {
        SSLSocketFactory existing = configSslSocketFactory;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (configSslSocketFactory == null) {
                configSslSocketFactory = ConfigTlsSocketFactory.create(context);
            }
            return configSslSocketFactory;
        }
    }

    private File cacheDirectory() {
        File directory = new File(context.getFilesDir(), "radio-config");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("cannot create radio config cache");
        }
        return directory;
    }

    private File cacheDirectoryForRead() throws IOException {
        File directory = new File(context.getFilesDir(), "radio-config");
        if (!directory.isDirectory()) {
            throw new IOException("radio config cache is missing");
        }
        return directory;
    }

    private void writePendingLocked(JSONObject config) throws IOException {
        File directory = cacheDirectory();
        File pending = new File(directory, PENDING_FILE);
        File temporary = new File(directory, TEMP_FILE);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(config.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        deleteIfPresent(pending, "cannot replace pending config");
        if (!temporary.renameTo(pending)) {
            throw new IOException("cannot stage downloaded config");
        }
    }

    private void discardPendingLocked() throws IOException {
        File directory = cacheDirectory();
        deleteIfPresent(new File(directory, PENDING_FILE), "cannot discard pending config");
        deleteIfPresent(new File(directory, TEMP_FILE), "cannot discard temporary config");
    }

    static void promotePendingFiles(File directory) throws IOException {
        File active = new File(directory, ACTIVE_FILE);
        File previous = new File(directory, PREVIOUS_FILE);
        File pending = new File(directory, PENDING_FILE);
        File previousBackup = new File(directory, PREVIOUS_BACKUP_FILE);
        if (!pending.isFile()) {
            throw new IOException("pending config is missing");
        }
        deleteIfPresent(previousBackup, "cannot prepare previous config backup");
        boolean previousBackedUp = false;
        if (previous.isFile()) {
            if (!previous.renameTo(previousBackup)) {
                throw new IOException("cannot preserve previous config backup");
            }
            previousBackedUp = true;
        }
        boolean activeMoved = false;
        if (active.isFile()) {
            if (!active.renameTo(previous)) {
                if (previousBackedUp && !previousBackup.renameTo(previous)) {
                    throw new IOException("cannot preserve active or restore previous config");
                }
                throw new IOException("cannot preserve active config");
            }
            activeMoved = true;
        }
        if (!pending.renameTo(active)) {
            if (activeMoved && !previous.renameTo(active)) {
                throw new IOException("cannot activate pending or restore active config");
            }
            if (previousBackedUp && !previousBackup.renameTo(previous)) {
                throw new IOException("cannot activate pending or restore previous config");
            }
            throw new IOException("cannot activate pending config");
        }
        deleteIfPresent(previousBackup, "cannot remove previous config backup");
    }

    static void installProvisionedFiles(File directory, byte[] config) throws IOException {
        if (directory == null || config == null || config.length == 0
                || config.length > MAX_CONFIG_BYTES) {
            throw new IOException("invalid provisioned config");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("cannot create radio config directory");
        }
        File temporary = new File(directory, PROVISIONED_TEMP_FILE);
        File pending = new File(directory, PENDING_FILE);
        deleteIfPresent(temporary, "cannot replace provisioned temporary config");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(config);
            output.getFD().sync();
        }
        deleteIfPresent(pending, "cannot replace pending config during provisioning");
        if (!temporary.renameTo(pending)) {
            throw new IOException("cannot stage provisioned config");
        }
        promotePendingFiles(directory);
    }

    static void rollbackFiles(File directory) throws IOException {
        File active = new File(directory, ACTIVE_FILE);
        File previous = new File(directory, PREVIOUS_FILE);
        File rollbackTemporary = new File(directory, ROLLBACK_TEMP_FILE);
        if (!previous.isFile()) {
            throw new IOException("previous config is missing");
        }
        deleteIfPresent(rollbackTemporary, "cannot clear rollback temporary config");
        boolean activeMoved = false;
        if (active.isFile()) {
            if (!active.renameTo(rollbackTemporary)) {
                throw new IOException("cannot preserve current config during rollback");
            }
            activeMoved = true;
        }
        if (!previous.renameTo(active)) {
            if (activeMoved && !rollbackTemporary.renameTo(active)) {
                throw new IOException("cannot roll back or restore current config");
            }
            throw new IOException("cannot restore previous config");
        }
        deleteIfPresent(rollbackTemporary, "cannot remove rolled-back config");
    }

    private static void deleteIfPresent(File file, String error) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException(error);
        }
    }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        try (InputStream input = new FileInputStream(file)) {
            return new JSONObject(readLimited(input));
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_CONFIG_BYTES) {
                throw new IOException("config exceeds maximum size");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static boolean isSafePathPart(String value) {
        return value != null && value.matches("[a-z0-9-]{1,64}");
    }

    /** Generic hold used when a registered device config does not exist. */
    public static final class DeviceConfigUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        public DeviceConfigUnavailableException() {
            super("device config unavailable");
        }
    }
}
