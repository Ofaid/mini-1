/*
 * Copyright (C) 2026 The Minimum contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.minimum.wifiprovisioner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/** DUMP-protected shell entry point for importing a temporary Wi-Fi request. */
public final class WifiProvisionReceiver extends BroadcastReceiver {
    public static final String ACTION_IMPORT_REQUEST =
            "dev.minimum.wifiprovisioner.action.IMPORT_REQUEST";
    public static final String ACTION_STATUS =
            "dev.minimum.wifiprovisioner.action.STATUS";
    public static final String EXTRA_REQUEST_PATH = "requestPath";
    public static final String EXTRA_OPERATION_ID = "operationId";

    private static final String REQUEST_FILE = "request.json";
    private static final String RESULT_FILE = "result.json";
    private static final String RECEIVER_STATUS_FILE = "receiver-status.json";
    private static final String REQUEST_DIRECTORY = "/data/local/tmp";
    private static final Pattern OPERATION_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SAFE_ERROR = Pattern.compile("^[a-z0-9-]{1,64}$");
    private static final int MAX_REQUEST_BYTES = 8192;
    private static final int MAX_RESULT_BYTES = 4096;

    private static final String STATE_IMPORTED = "imported";
    private static final String STATE_SUCCESS = "success";
    private static final String STATE_ERROR = "error";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_IMPORT_REQUEST.equals(action)) {
            importRequest(context,
                    intent.getStringExtra(EXTRA_REQUEST_PATH),
                    intent.getStringExtra(EXTRA_OPERATION_ID));
        } else if (ACTION_STATUS.equals(action)) {
            reportStatus(context, intent.getStringExtra(EXTRA_OPERATION_ID));
        }
    }

    private void importRequest(Context context, String requestedPath, String operationId) {
        setResultCode(0);
        setResultData(null);
        boolean validOperation = isValidOperationId(operationId);
        try {
            if (!validOperation) {
                throw new IOException("invalid-operation");
            }
            File source = validateRequestPath(requestedPath, operationId);
            clearPrivateState(context);
            byte[] request = readAndValidateRequest(source);
            writeBytes(new File(context.getFilesDir(), REQUEST_FILE), request);
            writeStatus(context, operationId, STATE_IMPORTED, null);

            try {
                Intent activity = new Intent(context, WifiProvisionActivity.class);
                activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(activity);
            } catch (RuntimeException startFailure) {
                throw new IllegalStateException("activity-start-failed", startFailure);
            }
            setResultCode(-1);
            setResultData(marker(STATE_IMPORTED, operationId, null));
        } catch (Exception error) {
            bestEffortDelete(new File(context.getFilesDir(), REQUEST_FILE));
            bestEffortDelete(new File(context.getFilesDir(), RESULT_FILE));
            if (validOperation) {
                String safeError = safeImportError(error);
                try {
                    writeStatus(context, operationId, STATE_ERROR, safeError);
                } catch (Exception ignored) {
                    // The host fails closed if no nonce-bound status is available.
                }
                setResultData(marker(STATE_ERROR, operationId, safeError));
            }
        }
    }

    private void reportStatus(Context context, String operationId) {
        setResultCode(0);
        setResultData(null);
        if (!isValidOperationId(operationId)) {
            return;
        }
        try {
            Status privateStatus = readStatus(context);
            if (privateStatus == null || !operationId.equals(privateStatus.operationId)) {
                return;
            }
            if (STATE_ERROR.equals(privateStatus.state)) {
                setResultCode(-1);
                setResultData(marker(STATE_ERROR, operationId, privateStatus.error));
                return;
            }
            if (STATE_SUCCESS.equals(privateStatus.state)) {
                setResultCode(-1);
                setResultData(marker(STATE_SUCCESS, operationId, null));
                return;
            }
            if (!STATE_IMPORTED.equals(privateStatus.state)) {
                return;
            }

            File resultFile = new File(context.getFilesDir(), RESULT_FILE);
            if (!resultFile.isFile()) {
                setResultCode(-1);
                setResultData(marker(STATE_IMPORTED, operationId, null));
                return;
            }
            JSONObject rawResult = new JSONObject(readSmallFile(resultFile, MAX_RESULT_BYTES));
            Status terminal = sanitizeActivityResult(operationId, rawResult);
            if (terminal == null) {
                return;
            }
            writeStatus(context, terminal.operationId, terminal.state, terminal.error);
            setResultCode(-1);
            setResultData(marker(terminal.state, terminal.operationId, terminal.error));
        } catch (Exception ignored) {
            // Missing or partially-written private state is never treated as success.
        }
    }

    private static File validateRequestPath(String requestedPath, String operationId)
            throws IOException {
        if (requestedPath == null || !isValidOperationId(operationId)
                || !requestedPath.startsWith(REQUEST_DIRECTORY + "/")) {
            throw new IOException("invalid-request");
        }
        File requested = new File(requestedPath);
        String canonicalPath = requested.getCanonicalPath();
        if (!requestedPath.equals(canonicalPath)) {
            throw new IOException("invalid-request");
        }
        File canonical = new File(canonicalPath);
        File parent = canonical.getParentFile();
        String expectedName = "minimum-wifi-" + operationId + ".json";
        if (parent == null
                || !REQUEST_DIRECTORY.equals(parent.getCanonicalPath())
                || !expectedName.equals(canonical.getName())) {
            throw new IOException("invalid-request");
        }
        return canonical;
    }

    private static byte[] readAndValidateRequest(File source) throws Exception {
        byte[] data = readBytes(source, MAX_REQUEST_BYTES, "invalid-request");
        JSONObject request = new JSONObject(new String(data, StandardCharsets.UTF_8));
        Iterator<String> keys = request.keys();
        int keyCount = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            keyCount++;
            if (!("ssid".equals(key) || "psk".equals(key))) {
                throw new IllegalArgumentException("invalid-request");
            }
        }
        if (keyCount != 2) {
            throw new IllegalArgumentException("invalid-request");
        }
        Object ssidValue = request.opt("ssid");
        Object pskValue = request.opt("psk");
        if (!(ssidValue instanceof String) || !(pskValue instanceof String)) {
            throw new IllegalArgumentException("invalid-request");
        }
        validateCredentials((String) ssidValue, (String) pskValue);
        return data;
    }

    private static void validateCredentials(String ssid, String psk) {
        int ssidBytes = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (ssidBytes < 1 || ssidBytes > 32) {
            throw new IllegalArgumentException("invalid-ssid");
        }
        if (!(psk.length() >= 8 && psk.length() <= 63)
                && !psk.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("invalid-wpa2-psk");
        }
    }

    private static void clearPrivateState(Context context) throws IOException {
        deleteIfPresent(new File(context.getFilesDir(), REQUEST_FILE));
        deleteIfPresent(new File(context.getFilesDir(), RESULT_FILE));
        deleteIfPresent(new File(context.getFilesDir(), RECEIVER_STATUS_FILE));
    }

    private static void writeStatus(Context context, String operationId, String state, String error)
            throws Exception {
        JSONObject value = new JSONObject();
        value.put("operationId", operationId);
        value.put("state", state);
        if (error != null) {
            value.put("error", error);
        }
        writeBytes(new File(context.getFilesDir(), RECEIVER_STATUS_FILE),
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Status readStatus(Context context) throws Exception {
        File file = new File(context.getFilesDir(), RECEIVER_STATUS_FILE);
        if (!file.isFile()) {
            return null;
        }
        JSONObject value = new JSONObject(readSmallFile(file, MAX_RESULT_BYTES));
        if (!hasOnly(value, "operationId", "state", "error")) {
            return null;
        }
        String operationId = value.optString("operationId", "");
        String state = value.optString("state", "");
        String error = value.has("error") ? value.optString("error", "") : null;
        if (!isValidOperationId(operationId)) {
            return null;
        }
        if (STATE_IMPORTED.equals(state) || STATE_SUCCESS.equals(state)) {
            return error == null ? new Status(operationId, state, null) : null;
        }
        if (STATE_ERROR.equals(state) && error != null && SAFE_ERROR.matcher(error).matches()) {
            return new Status(operationId, state, error);
        }
        return null;
    }

    private static Status sanitizeActivityResult(String operationId, JSONObject result) {
        Object ok = result.opt("ok");
        if (!(ok instanceof Boolean)) {
            return null;
        }
        if (((Boolean) ok).booleanValue()) {
            Object networkId = result.opt("networkId");
            if (!(networkId instanceof Number) || !hasExactly(result, "ok", "networkId")) {
                return null;
            }
            return new Status(operationId, STATE_SUCCESS, null);
        }
        Object error = result.opt("error");
        if (error instanceof String && SAFE_ERROR.matcher((String) error).matches()
                && hasExactly(result, "ok", "error")) {
            return new Status(operationId, STATE_ERROR, (String) error);
        }
        return null;
    }

    private static boolean hasOnly(JSONObject object, String... allowed) {
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean found = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasExactly(JSONObject object, String... expected) {
        int count = 0;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            count++;
            boolean found = false;
            for (String candidate : expected) {
                if (candidate.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return count == expected.length;
    }

    private static byte[] readBytes(File file, int maxBytes, String error) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > maxBytes) {
            throw new IOException(error);
        }
        int size = (int) file.length();
        byte[] data = new byte[size];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    throw new IOException(error);
                }
                offset += read;
            }
            if (input.read() >= 0 || file.length() != size) {
                throw new IOException(error);
            }
        }
        return data;
    }

    private static String readSmallFile(File file, int maxBytes) throws IOException {
        return new String(readBytes(file, maxBytes, "invalid-status"), StandardCharsets.UTF_8);
    }

    private static void writeBytes(File file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value);
            output.getFD().sync();
        }
    }

    private static String marker(String state, String operationId, String error) {
        String prefix = state.toUpperCase(Locale.US);
        if (STATE_ERROR.equals(state)) {
            return prefix + ":" + operationId + ":" + error;
        }
        return prefix + ":" + operationId;
    }

    private static String safeImportError(Exception error) {
        String message = error.getMessage();
        if (message != null && SAFE_ERROR.matcher(message).matches()) {
            return message;
        }
        return "invalid-request";
    }

    private static boolean isValidOperationId(String operationId) {
        return operationId != null && OPERATION_ID.matcher(operationId).matches();
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("file-cleanup-failed");
        }
    }

    private static void bestEffortDelete(File file) {
        if (file.exists()) {
            file.delete();
        }
    }

    private static final class Status {
        final String operationId;
        final String state;
        final String error;

        Status(String operationId, String state, String error) {
            this.operationId = operationId;
            this.state = state;
            this.error = error;
        }
    }
}
