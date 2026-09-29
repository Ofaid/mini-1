/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.model.IChannel;

/** Resolves logical config room paths against a newly synchronized Mumble channel tree. */
public final class RoomPathResolver {
    private RoomPathResolver() {
    }

    @Nullable
    public static IChannel resolve(IChannel root, String fullPath) {
        if (root == null || fullPath == null || !fullPath.startsWith("/")) {
            return null;
        }
        if ("/".equals(fullPath)) {
            return root;
        }

        String[] segments = fullPath.substring(1).split("/", -1);
        IChannel current = root;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return null;
            }
            IChannel next = null;
            for (IChannel child : safeSubchannels(current)) {
                if (segment.equals(child.getName())) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    public static String fullPath(IChannel channel) {
        if (channel == null) {
            return "/";
        }
        List<String> segments = new ArrayList<>();
        IChannel current = channel;
        while (current != null && current.getParent() != null) {
            segments.add(current.getName());
            current = current.getParent();
        }
        Collections.reverse(segments);
        if (segments.isEmpty()) {
            return "/";
        }
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            path.append('/').append(segment);
        }
        return path.toString();
    }

    private static List<? extends IChannel> safeSubchannels(IChannel channel) {
        List<? extends IChannel> subchannels = channel.getSubchannels();
        return subchannels == null ? Collections.emptyList() : subchannels;
    }
}
