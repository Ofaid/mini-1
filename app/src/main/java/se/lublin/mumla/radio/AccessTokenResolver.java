/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package se.lublin.mumla.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves public access tokens for one configured Minimum channel. */
public final class AccessTokenResolver {
    private AccessTokenResolver() {
    }

    /**
     * Returns non-blank public tokens for one channel, without duplicates. Protected references
     * remain unresolved until a device-local secure store is available.
     */
    public static List<String> resolve(JSONObject channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        Object accessValue = channel.opt("access");
        if (!(accessValue instanceof JSONObject)) {
            return Collections.emptyList();
        }
        JSONObject access = (JSONObject) accessValue;
        if (!"public".equals(access.opt("mode"))) {
            return Collections.emptyList();
        }

        Set<String> tokens = new LinkedHashSet<>();
        addToken(tokens, access.opt("token"));
        Object tokenArray = access.opt("tokens");
        if (tokenArray instanceof JSONArray) {
            JSONArray values = (JSONArray) tokenArray;
            for (int index = 0; index < values.length(); index++) {
                addToken(tokens, values.opt(index));
            }
        }
        return tokens.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tokens));
    }

    private static void addToken(Set<String> tokens, Object value) {
        if (value instanceof String) {
            String token = ((String) value).trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
    }
}
