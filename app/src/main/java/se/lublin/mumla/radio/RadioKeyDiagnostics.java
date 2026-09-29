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
import android.view.InputDevice;
import android.view.KeyEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Stores a small app-private hardware-key trace for managed-radio commissioning. */
public final class RadioKeyDiagnostics {
    public static final String RELATIVE_LOG_PATH = "radio-diagnostics/key-events.log";
    static final int MAX_LOG_BYTES = 32 * 1024;
    private static final Object LOCK = new Object();

    private RadioKeyDiagnostics() {
    }

    public static void record(Context context, String path, KeyEvent event) {
        if (context == null || event == null
                || !RadioPttKeyManager.isRadioProfile(RadioDeviceProfile.detectCurrent())
                || (!RadioPttKeyManager.isDiagnosticHardwareKey(event.getKeyCode())
                && !(RadioDeviceProfile.RYKS.equals(RadioDeviceProfile.detectCurrent())
                && event.getScanCode() > 0))) {
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 1) {
            return;
        }

        InputDevice device = event.getDevice();
        String deviceName = device == null ? "unknown" : sanitize(device.getName());
        String action = event.getAction() == KeyEvent.ACTION_DOWN ? "DOWN"
                : event.getAction() == KeyEvent.ACTION_UP ? "UP" : "OTHER";
        String line = String.format(Locale.ROOT,
                "eventTime=%d path=%s action=%s keyCode=%d keyName=%s scanCode=%d"
                        + " repeat=%d deviceId=%d source=0x%08X device=%s%n",
                event.getEventTime(), sanitize(path), action, event.getKeyCode(),
                KeyEvent.keyCodeToString(event.getKeyCode()), event.getScanCode(),
                event.getRepeatCount(), event.getDeviceId(), event.getSource(), deviceName);

        synchronized (LOCK) {
            File directory = new File(context.getFilesDir(), "radio-diagnostics");
            if (!directory.exists() && !directory.mkdirs()) {
                return;
            }
            File log = new File(directory, "key-events.log");
            try {
                byte[] retained = appendRecord(readTail(log),
                        line.getBytes(StandardCharsets.UTF_8), MAX_LOG_BYTES);
                if (retained == null) {
                    return;
                }
                try (FileOutputStream stream = new FileOutputStream(log, false)) {
                    stream.write(retained);
                }
            } catch (IOException ignored) {
                // Diagnostics must never interfere with PTT delivery or key release.
            }
        }
    }

    /**
     * Appends one complete record while retaining the newest complete records under the limit.
     * A record that cannot fit by itself is rejected so a single event can never overflow the log.
     */
    static byte[] appendRecord(byte[] existing, byte[] record, int maxBytes) {
        if (record == null || record.length == 0 || maxBytes <= 0
                || record.length > maxBytes || record[record.length - 1] != '\n') {
            return null;
        }
        byte[] current = existing == null ? new byte[0] : existing;
        if (current.length > maxBytes) {
            current = Arrays.copyOfRange(current, current.length - maxBytes, current.length);
        }
        long combinedLength = (long) current.length + record.length;
        if (combinedLength > Integer.MAX_VALUE) {
            return null;
        }
        byte[] combined = new byte[(int) combinedLength];
        System.arraycopy(current, 0, combined, 0, current.length);
        System.arraycopy(record, 0, combined, current.length, record.length);
        if (combined.length <= maxBytes) {
            return combined;
        }
        return retainNewestCompleteRecords(combined, maxBytes);
    }

    private static byte[] readTail(File log) throws IOException {
        long length = log.length();
        if (length <= 0L) {
            return new byte[0];
        }
        long offset = Math.max(0L, length - MAX_LOG_BYTES);
        int expected = (int) Math.min((long) MAX_LOG_BYTES, length - offset);
        byte[] result = new byte[expected];
        try (FileInputStream stream = new FileInputStream(log)) {
            long skipped = 0L;
            while (skipped < offset) {
                long count = stream.skip(offset - skipped);
                if (count > 0L) {
                    skipped += count;
                    continue;
                }
                if (stream.read() < 0) {
                    return new byte[0];
                }
                skipped++;
            }
            int position = 0;
            while (position < expected) {
                int count = stream.read(result, position, expected - position);
                if (count < 0) {
                    break;
                }
                position += count;
            }
            return position == expected ? result : Arrays.copyOf(result, position);
        }
    }

    private static byte[] retainNewestCompleteRecords(byte[] bytes, int maxBytes) {
        int lastNewline = lastIndexOfNewline(bytes, bytes.length - 1);
        if (lastNewline < 0) {
            return null;
        }
        int end = lastNewline + 1;
        int start = lastIndexOfNewline(bytes, end - 2) + 1;
        if (end - start > maxBytes) {
            return null;
        }
        while (start > 0) {
            int previousStart = lastIndexOfNewline(bytes, start - 2) + 1;
            if (end - previousStart > maxBytes) {
                break;
            }
            start = previousStart;
        }
        return Arrays.copyOfRange(bytes, start, end);
    }

    private static int lastIndexOfNewline(byte[] bytes, int fromIndex) {
        for (int index = Math.min(fromIndex, bytes.length - 1); index >= 0; index--) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replace('\n', '_').replace('\r', '_').replace('\t', '_');
    }
}
