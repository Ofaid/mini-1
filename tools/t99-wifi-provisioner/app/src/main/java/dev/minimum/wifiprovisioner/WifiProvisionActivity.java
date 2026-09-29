/*
 * Copyright (C) 2026 The Minimum contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package dev.minimum.wifiprovisioner;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** One-shot API-21/22 provisioning helper. It is installed and removed by prepare-t99.ps1. */
@SuppressWarnings("deprecation")
public final class WifiProvisionActivity extends Activity {
    private static final String REQUEST_FILE = "request.json";
    private static final String RESULT_FILE = "result.json";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setText("Provisioning lab Wi-Fi…");
        setContentView(status);
        new Thread(() -> provision(status), "minimum-wifi-provision").start();
    }

    private void provision(TextView status) {
        File requestFile = new File(getFilesDir(), REQUEST_FILE);
        File resultFile = new File(getFilesDir(), RESULT_FILE);
        try {
            resultFile.delete();
            JSONObject request = new JSONObject(readSmallFile(requestFile));
            String ssid = request.getString("ssid");
            String psk = request.getString("psk");
            validate(ssid, psk);

            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                throw new IllegalStateException("wifi-service-unavailable");
            }
            if (!wifi.isWifiEnabled()) {
                if (!wifi.setWifiEnabled(true)) {
                    throw new IllegalStateException("wifi-enable-rejected");
                }
                long deadline = System.currentTimeMillis() + 15000L;
                while (!wifi.isWifiEnabled() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250L);
                }
                if (!wifi.isWifiEnabled()) {
                    throw new IllegalStateException("wifi-enable-timeout");
                }
            }

            WifiConfiguration configuration = new WifiConfiguration();
            configuration.SSID = quote(ssid);
            configuration.preSharedKey = psk.matches("(?i)^[0-9a-f]{64}$") ? psk : quote(psk);
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            configuration.status = WifiConfiguration.Status.ENABLED;

            int existingNetworkId = findNetworkId(wifi.getConfiguredNetworks(), ssid);
            int networkId;
            if (existingNetworkId >= 0) {
                configuration.networkId = existingNetworkId;
                networkId = wifi.updateNetwork(configuration);
            } else {
                networkId = wifi.addNetwork(configuration);
            }
            if (networkId < 0) {
                throw new IllegalStateException("wifi-config-rejected");
            }
            if (!wifi.saveConfiguration()) {
                throw new IllegalStateException("wifi-config-not-saved");
            }
            if (!wifi.enableNetwork(networkId, true)) {
                throw new IllegalStateException("wifi-network-not-enabled");
            }
            wifi.reconnect();

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("networkId", networkId);
            writeFile(resultFile, result.toString());
            runOnUiThread(() -> status.setText("Lab Wi-Fi saved"));
        } catch (Exception error) {
            try {
                JSONObject result = new JSONObject();
                result.put("ok", false);
                result.put("error", safeError(error));
                writeFile(resultFile, result.toString());
            } catch (Exception ignored) {
                // The provisioning script will report a missing result file.
            }
            runOnUiThread(() -> status.setText("Wi-Fi provisioning failed"));
        } finally {
            requestFile.delete();
        }
    }

    private static int findNetworkId(List<WifiConfiguration> configurations, String ssid) {
        if (configurations == null) {
            return -1;
        }
        String quoted = quote(ssid);
        for (WifiConfiguration configuration : configurations) {
            if (quoted.equals(configuration.SSID) || ssid.equals(configuration.SSID)) {
                return configuration.networkId;
            }
        }
        return -1;
    }

    private static void validate(String ssid, String psk) {
        int ssidBytes = ssid.getBytes(StandardCharsets.UTF_8).length;
        if (ssidBytes < 1 || ssidBytes > 32) {
            throw new IllegalArgumentException("invalid-ssid");
        }
        if (!(psk.length() >= 8 && psk.length() <= 63)
                && !psk.matches("(?i)^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("invalid-wpa2-psk");
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String readSmallFile(File file) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > 8192) {
            throw new IllegalArgumentException("invalid-request-file");
        }
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != data.length) {
                throw new IllegalStateException("incomplete-request-file");
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeFile(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || !message.matches("^[a-z0-9-]{1,64}$")) {
            return "unexpected-error";
        }
        return message.toLowerCase(Locale.US);
    }
}
