/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio.tracking;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.StatFs;
import android.telephony.TelephonyManager;

import java.util.Locale;

/** A redacted, compact health snapshot suitable for an APRS comment. */
public final class AprsHealthSnapshot {
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private final int batteryPercent;
    private final boolean charging;
    private final int batteryTemperatureC10;
    private final int wifiRssiDbm;
    private final String mobileType;
    private final int mobileRssiDbm;
    private final long freeStorageMb;

    AprsHealthSnapshot(int batteryPercent, boolean charging, int batteryTemperatureC10,
                       int wifiRssiDbm, String mobileType, int mobileRssiDbm,
                       long freeStorageMb) {
        this.batteryPercent = batteryPercent;
        this.charging = charging;
        this.batteryTemperatureC10 = batteryTemperatureC10;
        this.wifiRssiDbm = wifiRssiDbm;
        this.mobileType = mobileType == null ? "" : mobileType;
        this.mobileRssiDbm = mobileRssiDbm;
        this.freeStorageMb = freeStorageMb;
    }

    public static AprsHealthSnapshot capture(Context context, int mobileRssiDbm) {
        if (context == null) {
            return new AprsHealthSnapshot(UNKNOWN, false, UNKNOWN, UNKNOWN, "",
                    mobileRssiDbm, UNKNOWN);
        }
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = battery == null ? UNKNOWN : battery.getIntExtra(BatteryManager.EXTRA_LEVEL,
                UNKNOWN);
        int scale = battery == null ? UNKNOWN : battery.getIntExtra(BatteryManager.EXTRA_SCALE,
                UNKNOWN);
        int percent = level >= 0 && scale > 0 ? Math.min(100, Math.max(0,
                Math.round(level * 100.0f / scale))) : UNKNOWN;
        int status = battery == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int temperature = battery == null ? UNKNOWN
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN);

        int wifiRssi = UNKNOWN;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info != null && info.getNetworkId() >= 0) {
                wifiRssi = info.getRssi();
            }
        } catch (RuntimeException ignored) {
            // Wi-Fi may be unavailable or permission-restricted on an OEM build.
        }

        String mobileType = "";
        try {
            TelephonyManager telephony = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);
            if (telephony != null) {
                mobileType = networkTypeCode(telephony.getNetworkType());
            }
        } catch (SecurityException ignored) {
            // Keep the rest of the health snapshot when phone state is unavailable.
        }

        long freeStorageMb = UNKNOWN;
        try {
            StatFs stats = new StatFs(context.getFilesDir().getAbsolutePath());
            freeStorageMb = (stats.getAvailableBlocksLong() * stats.getBlockSizeLong())
                    / (1024L * 1024L);
        } catch (RuntimeException ignored) {
            // Storage statistics are optional health data.
        }
        return new AprsHealthSnapshot(percent, charging, temperature, wifiRssi,
                mobileType, mobileRssiDbm, freeStorageMb);
    }

    static String networkTypeCode(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
                return "3G";
            default:
                return type == TelephonyManager.NETWORK_TYPE_UNKNOWN ? "" : "M";
        }
    }

    /** Formats health as a compact ASCII comment; no identifiers or coordinates are included. */
    public String toAprsComment(AprsBeaconCoordinator.MovementState state) {
        return toAprsComment(state, TrackingFix.UNKNOWN);
    }

    /** Adds the accuracy of the fix used by the position report when it is available. */
    public String toAprsComment(AprsBeaconCoordinator.MovementState state,
                                float accuracyMeters) {
        String stateCode = state == null ? "ST" : state == AprsBeaconCoordinator.MovementState.VEHICLE
                ? "VE" : state == AprsBeaconCoordinator.MovementState.WALKING ? "WA" : "ST";
        StringBuilder result = new StringBuilder("T56 ").append(stateCode);
        if (Float.isFinite(accuracyMeters) && accuracyMeters > 0.0f) {
            result.append(" A").append(Math.round(accuracyMeters));
        }
        if (batteryPercent != UNKNOWN) {
            result.append(" B").append(batteryPercent).append('%');
            if (charging) {
                result.append('+');
            }
        }
        if (batteryTemperatureC10 != UNKNOWN) {
            result.append(" T").append(String.format(Locale.ROOT, "%.1f",
                    batteryTemperatureC10 / 10.0f));
        }
        if (wifiRssiDbm != UNKNOWN) {
            result.append(" W").append(wifiRssiDbm);
        }
        if (!mobileType.isEmpty()) {
            result.append(' ').append(mobileType);
            if (mobileRssiDbm != UNKNOWN) {
                result.append(mobileRssiDbm);
            } else {
                result.append("NA");
            }
        } else {
            result.append(" MNA");
        }
        if (freeStorageMb != UNKNOWN) {
            result.append(" S").append(Math.max(0L, freeStorageMb / 1024L)).append('G');
        }
        return result.toString();
    }
}
