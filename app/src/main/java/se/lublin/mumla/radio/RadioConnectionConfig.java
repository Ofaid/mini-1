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
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import se.lublin.humla.model.Server;

/** Immutable, validated subset of radio config used by the live connection/UI path. */
public final class RadioConnectionConfig {
    private static final int MAX_CHANNELS = 16;
    private static final int MAX_CHANNEL_ALIAS_LENGTH = 32;

    private final int configVersion;
    private final String serviceName;
    private final boolean autoConnect;
    private final boolean autoReconnect;
    private final int maximumTxSeconds;
    private final List<Channel> channels;
    private final int defaultChannelIndex;

    private RadioConnectionConfig(int configVersion, String serviceName, boolean autoConnect,
                                  boolean autoReconnect, int maximumTxSeconds,
                                  List<Channel> channels,
                                  int defaultChannelIndex) {
        this.configVersion = configVersion;
        this.serviceName = serviceName;
        this.autoConnect = autoConnect;
        this.autoReconnect = autoReconnect;
        this.maximumTxSeconds = maximumTxSeconds;
        this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
        this.defaultChannelIndex = defaultChannelIndex;
    }

    public static RadioConnectionConfig fromJson(JSONObject config) throws JSONException {
        RadioConfigRepository.validateConfig(config, null);

        JSONObject service = config.optJSONObject("service");
        String serviceName = service == null
                ? "Minimum"
                : requireNonBlank(service.optString("name", "Minimum"), "service name");
        JSONObject radio = config.getJSONObject("radio");
        JSONObject ptt = config.getJSONObject("ptt");
        int maximumTxSeconds = ptt.getInt("maximumTxSeconds");
        String defaultChannelId = requireIdentifier(
                radio.optString("defaultChannel", ""), "default channel");

        Map<String, Connection> connections = parseConnections(
                config.getJSONObject("connections"), serviceName);
        JSONArray channelArray = config.getJSONArray("channels");
        if (channelArray.length() > MAX_CHANNELS) {
            throw new JSONException("too many radio channels");
        }

        List<Channel> channels = new ArrayList<>();
        Set<String> channelIds = new HashSet<>();
        int defaultChannelIndex = -1;
        for (int index = 0; index < channelArray.length(); index++) {
            JSONObject channelJson = channelArray.optJSONObject(index);
            if (channelJson == null) {
                throw new JSONException("invalid channel entry");
            }
            String id = requireIdentifier(channelJson.optString("id", ""), "channel id");
            if (!channelIds.add(id)) {
                throw new JSONException("duplicate channel id");
            }
            String connectionId = requireIdentifier(
                    channelJson.optString("connectionId", ""), "connection id");
            Connection connection = connections.get(connectionId);
            if (connection == null) {
                throw new JSONException("channel references missing connection");
            }
            List<String> accessTokens = validateAndResolveAccess(channelJson);
            String label = requireNonBlank(channelJson.optString("label", ""), "channel label");
            String alias = channelJson.has("alias")
                    ? normalizeChannelAlias(channelJson.opt("alias"), "channel alias")
                    : label;
            Channel channel = new Channel(
                    id,
                    label,
                    alias,
                    normalizePath(channelJson.optString("path", "")),
                    channelJson.optString("presetKey", ""),
                    connection,
                    accessTokens);
            if (id.equals(defaultChannelId)) {
                defaultChannelIndex = channels.size();
            }
            channels.add(channel);
        }
        if (defaultChannelIndex < 0) {
            throw new JSONException("default channel is missing");
        }

        return new RadioConnectionConfig(
                config.getInt("configVersion"),
                serviceName,
                radio.optBoolean("autoConnect", false),
                radio.optBoolean("autoReconnect", true),
                maximumTxSeconds,
                channels,
                defaultChannelIndex);
    }

    private static List<String> validateAndResolveAccess(JSONObject channel) throws JSONException {
        JSONObject access = channel.optJSONObject("access");
        if (access == null || !(access.opt("mode") instanceof String)) {
            throw new JSONException("invalid channel access policy");
        }
        String mode = access.optString("mode", "");
        if ("none".equals(mode)) {
            return Collections.emptyList();
        }
        if ("protected".equals(mode)) {
            if (!(access.opt("tokenRef") instanceof String)) {
                throw new JSONException("invalid protected token reference");
            }
            requireNonBlank(access.optString("tokenRef", ""), "protected token reference");
            return Collections.emptyList();
        }
        if (!"public".equals(mode)) {
            throw new JSONException("unknown channel access mode");
        }
        if (access.has("token") && !(access.opt("token") instanceof String)) {
            throw new JSONException("invalid channel access token");
        }
        if (access.has("tokens")) {
            JSONArray values = access.optJSONArray("tokens");
            if (values == null) {
                throw new JSONException("invalid channel access token list");
            }
            for (int index = 0; index < values.length(); index++) {
                if (!(values.opt(index) instanceof String)) {
                    throw new JSONException("invalid channel access token list");
                }
            }
        }
        List<String> tokens = AccessTokenResolver.resolve(channel);
        if (tokens.isEmpty()) {
            throw new JSONException("public channel has no access token");
        }
        for (String token : tokens) {
            if (token.length() > 1024) {
                throw new JSONException("channel access token is too long");
            }
        }
        return tokens;
    }

    private static Map<String, Connection> parseConnections(JSONObject connectionObject,
                                                             String serviceName)
            throws JSONException {
        Map<String, Connection> result = new HashMap<>();
        Iterator<String> ids = connectionObject.keys();
        while (ids.hasNext()) {
            String id = requireIdentifier(ids.next(), "connection id");
            JSONObject value = connectionObject.optJSONObject(id);
            if (value == null) {
                throw new JSONException("invalid connection entry");
            }
            String host = requireNonBlank(value.optString("host", ""), "Mumble host");
            if (host.length() > 253 || !host.matches("[A-Za-z0-9.-]+")) {
                throw new JSONException("invalid Mumble host");
            }
            int port = value.optInt("port", -1);
            if (port < 1 || port > 65535) {
                throw new JSONException("invalid Mumble port");
            }
            String password = optionalString(value, "password", "server password", 1024);
            String name = value.has("name")
                    ? requireNonBlank(value.optString("name", ""), "connection name")
                    : serviceName;
            Connection connection = new Connection(
                    id,
                    name,
                    host,
                    port,
                    normalizeMumbleUsername(value.optString("username", "")),
                    password,
                    normalizeFingerprint(value.optString("serverCertificateSha256", "")),
                    value.optBoolean("autoTrustServerCertificate", true));
            result.put(id, connection);
        }
        if (result.isEmpty()) {
            throw new JSONException("no Mumble connections");
        }
        return result;
    }

    private static String optionalString(JSONObject object, String key, String field,
                                         int maximumLength) throws JSONException {
        if (!object.has(key)) {
            return "";
        }
        if (!(object.opt(key) instanceof String)) {
            throw new JSONException("invalid " + field + " type");
        }
        String value = object.optString(key, "");
        if (value.length() > maximumLength) {
            throw new JSONException(field + " is too long");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new JSONException(field + " contains a control character");
            }
        }
        return value;
    }

    private static String requireNonBlank(String value, String field) throws JSONException {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new JSONException(field + " is blank");
        }
        return trimmed;
    }

    private static String normalizeChannelAlias(Object rawValue, String field)
            throws JSONException {
        if (!(rawValue instanceof String)) {
            throw new JSONException("invalid " + field + " type");
        }
        String raw = (String) rawValue;
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new JSONException(field + " is blank");
        }
        if (!raw.equals(value)) {
            throw new JSONException(field + " has outer whitespace");
        }
        if (value.length() > MAX_CHANNEL_ALIAS_LENGTH) {
            throw new JSONException(field + " is too long");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new JSONException(field + " contains a control character");
            }
        }
        return value;
    }

    private static String requireIdentifier(String value, String field) throws JSONException {
        String identifier = requireNonBlank(value, field);
        if (identifier.length() > 64 || !identifier.matches("[A-Za-z0-9._-]+")) {
            throw new JSONException("invalid " + field);
        }
        return identifier;
    }

    static String normalizePath(String value) throws JSONException {
        String path = requireNonBlank(value, "channel path");
        if (!path.startsWith("/") || path.length() > 512 || path.contains("//")) {
            throw new JSONException("invalid channel path");
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    static String normalizeFingerprint(String value) throws JSONException {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.replace(":", "").replace(" ", "")
                .toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new JSONException("invalid server certificate fingerprint");
        }
        return normalized;
    }

    static String normalizeMumbleUsername(String value) throws JSONException {
        String username = requireNonBlank(value, "Mumble username");
        if (username.length() > 128) {
            throw new JSONException("Mumble username is too long");
        }
        for (int index = 0; index < username.length(); index++) {
            if (Character.isISOControl(username.charAt(index))) {
                throw new JSONException("Mumble username contains a control character");
            }
        }
        return username;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public String getServiceName() {
        return serviceName;
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public int getMaximumTxSeconds() {
        return maximumTxSeconds;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public int getDefaultChannelIndex() {
        return defaultChannelIndex;
    }

    public Channel getDefaultChannel() {
        return channels.get(defaultChannelIndex);
    }

    public int findChannelIndex(String channelId) {
        if (channelId != null) {
            for (int index = 0; index < channels.size(); index++) {
                if (channelId.equals(channels.get(index).getId())) {
                    return index;
                }
            }
        }
        return -1;
    }

    public static final class Connection {
        private final String id;
        private final String name;
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String serverCertificateSha256;
        private final boolean autoTrustServerCertificate;

        Connection(String id, String name, String host, int port, String username,
                   String password, String serverCertificateSha256,
                   boolean autoTrustServerCertificate) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.serverCertificateSha256 = serverCertificateSha256;
            this.autoTrustServerCertificate = autoTrustServerCertificate;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getServerCertificateSha256() {
            return serverCertificateSha256;
        }

        public boolean isAutoTrustServerCertificate() {
            return autoTrustServerCertificate;
        }

        /** A configured pin is stricter than automatic trust and must always match when present. */
        boolean acceptsServerCertificate(String actualSha256) {
            return serverCertificateSha256 == null
                    ? autoTrustServerCertificate
                    : serverCertificateSha256.equals(actualSha256);
        }

        boolean matches(Server target) {
            return target != null
                    && host.equalsIgnoreCase(target.getHost())
                    && port == target.getPort()
                    && username.equals(target.getUsername())
                    && password.equals(target.getPassword());
        }

        private boolean hasSameSessionCredentials(Connection other) {
            return other != null
                    && id.equals(other.id)
                    && host.equalsIgnoreCase(other.host)
                    && port == other.port
                    && username.equals(other.username)
                    && password.equals(other.password)
                    && equalsNullable(serverCertificateSha256, other.serverCertificateSha256)
                    && autoTrustServerCertificate == other.autoTrustServerCertificate;
        }

        private static boolean equalsNullable(String first, String second) {
            return first == null ? second == null : first.equals(second);
        }
    }

    public static final class Channel {
        private final String id;
        private final String label;
        private final String alias;
        private final String path;
        private final String presetKey;
        private final Connection connection;
        private final List<String> accessTokens;

        Channel(String id, String label, String alias, String path, String presetKey,
                Connection connection, List<String> accessTokens) {
            this.id = id;
            this.label = label;
            this.alias = alias;
            this.path = path;
            this.presetKey = presetKey == null ? "" : presetKey.trim();
            this.connection = connection;
            this.accessTokens = Collections.unmodifiableList(new ArrayList<>(accessTokens));
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        /** Short operator-facing name shown in Minimum; falls back to the legacy label. */
        public String getAlias() {
            return alias;
        }

        public String getPath() {
            return path;
        }

        public String getPresetKey() {
            return presetKey;
        }

        public Connection getConnection() {
            return connection;
        }

        public List<String> getAccessTokens() {
            return accessTokens;
        }

        public boolean requiresReconnectTo(Channel other) {
            return other == null
                    || !connection.hasSameSessionCredentials(other.connection)
                    || !accessTokens.equals(other.accessTokens);
        }
    }
}
