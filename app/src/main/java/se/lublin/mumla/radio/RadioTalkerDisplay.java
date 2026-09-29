/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import java.util.ArrayList;
import java.util.List;

/** Bounded, deterministic formatting for the small radio RX display. */
public final class RadioTalkerDisplay {
    private RadioTalkerDisplay() {
    }

    public static Display format(List<String> talkers, int maxLines, String overflowLabel) {
        List<String> names = new ArrayList<>();
        if (talkers != null) {
            for (String talker : talkers) {
                names.add(normalize(talker));
            }
        }
        int lineLimit = Math.max(1, maxLines);
        int visibleCount = Math.min(names.size(), lineLimit);
        int hiddenCount = names.size() - visibleCount;
        List<String> visible = new ArrayList<>(names.subList(0, visibleCount));
        if (hiddenCount > 0 && !visible.isEmpty()) {
            String suffix = overflowLabel == null ? "" : overflowLabel.trim();
            if (!suffix.isEmpty()) {
                int last = visible.size() - 1;
                visible.set(last, visible.get(last) + " " + suffix);
            }
        }
        String text = join(visible);
        String accessibilityText = join(names);
        if (hiddenCount > 0 && overflowLabel != null && !overflowLabel.trim().isEmpty()) {
            accessibilityText = accessibilityText + "\n" + overflowLabel.trim();
        }
        return new Display(text, accessibilityText, names.size(), visibleCount, hiddenCount);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(value);
        }
        return result.toString();
    }

    public static final class Display {
        private final String text;
        private final String accessibilityText;
        private final int totalCount;
        private final int visibleCount;
        private final int hiddenCount;

        private Display(String text, String accessibilityText, int totalCount,
                        int visibleCount, int hiddenCount) {
            this.text = text;
            this.accessibilityText = accessibilityText;
            this.totalCount = totalCount;
            this.visibleCount = visibleCount;
            this.hiddenCount = hiddenCount;
        }

        public String getText() {
            return text;
        }

        public String getAccessibilityText() {
            return accessibilityText;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public int getVisibleCount() {
            return visibleCount;
        }

        public int getHiddenCount() {
            return hiddenCount;
        }
    }
}
