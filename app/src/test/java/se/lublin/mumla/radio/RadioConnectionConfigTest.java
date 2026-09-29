package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class RadioConnectionConfigTest {
    @Test
    public void parsesPerChannelConnectionsPasswordsAndTokens() throws JSONException {
        RadioConnectionConfig config = RadioConnectionConfig.fromJson(new JSONObject(validConfig()));

        assertEquals(7, config.getConfigVersion());
        assertEquals("Minimum Test", config.getServiceName());
        assertTrue(config.isAutoConnect());
        assertFalse(config.isAutoReconnect());
        assertEquals(120, config.getMaximumTxSeconds());
        assertEquals(2, config.getChannels().size());

        RadioConnectionConfig.Channel main = config.getDefaultChannel();
        assertEquals("main", main.getId());
        assertEquals("Ops Main", main.getAlias());
        assertEquals("/PUBLIC/MAIN", main.getPath());
        assertEquals("voice-a.example.org", main.getConnection().getHost());
        assertEquals("server-a-password", main.getConnection().getPassword());
        assertEquals(Arrays.asList("PUBLIC-A", "SHARED"), main.getAccessTokens());
        assertTrue(main.getConnection().acceptsServerCertificate(
                "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"));

        RadioConnectionConfig.Channel other = config.getChannels().get(1);
        assertEquals("Other", other.getAlias());
        assertEquals("voice-b.example.org", other.getConnection().getHost());
        assertEquals("server-b-password", other.getConnection().getPassword());
        assertEquals(Arrays.asList("PUBLIC-B"), other.getAccessTokens());
        assertTrue(main.requiresReconnectTo(other));
    }

    @Test
    public void exposesValidatedMaximumTransmissionDuration() throws JSONException {
        JSONObject json = new JSONObject(validConfig());
        json.getJSONObject("ptt").put("maximumTxSeconds", 1);

        assertEquals(1, RadioConnectionConfig.fromJson(json).getMaximumTxSeconds());

        json.getJSONObject("ptt").put("maximumTxSeconds", 121);
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));
    }

    @Test
    public void sameConnectionAndTokensCanReuseSession() throws JSONException {
        JSONObject json = new JSONObject(validConfig());
        JSONObject second = json.getJSONArray("channels").getJSONObject(1);
        second.put("connectionId", "server-a");
        second.put("access", json.getJSONArray("channels").getJSONObject(0).getJSONObject("access"));
        RadioConnectionConfig config = RadioConnectionConfig.fromJson(json);

        assertFalse(config.getChannels().get(0).requiresReconnectTo(config.getChannels().get(1)));
    }

    @Test
    public void sameConnectionWithDifferentChannelTokenRequiresReconnect() throws JSONException {
        JSONObject json = new JSONObject(validConfig());
        json.getJSONArray("channels").getJSONObject(1).put("connectionId", "server-a");
        RadioConnectionConfig config = RadioConnectionConfig.fromJson(json);

        assertTrue(config.getChannels().get(0).requiresReconnectTo(config.getChannels().get(1)));
    }

    @Test
    public void certificateTrustPolicyIsConnectionScoped() throws JSONException {
        JSONObject json = new JSONObject(validConfig());
        JSONObject connection = json.getJSONObject("connections").getJSONObject("server-a");
        connection.remove("serverCertificateSha256");
        connection.put("autoTrustServerCertificate", false);
        RadioConnectionConfig config = RadioConnectionConfig.fromJson(json);

        assertFalse(config.getDefaultChannel().getConnection().acceptsServerCertificate(
                "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"));

        connection.put("autoTrustServerCertificate", "yes");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));
    }

    @Test
    public void restoresChannelByStableIdAndFallsBackWhenMissing() throws JSONException {
        RadioConnectionConfig config = RadioConnectionConfig.fromJson(new JSONObject(validConfig()));

        assertEquals(1, config.findChannelIndex("other"));
        assertEquals(-1, config.findChannelIndex("removed"));
        assertEquals(-1, config.findChannelIndex(null));
    }

    @Test
    public void rejectsUnsafeConnectionAndMissingDefaultChannel() throws JSONException {
        JSONObject unsafeHost = new JSONObject(validConfig());
        unsafeHost.getJSONObject("connections").getJSONObject("server-a")
                .put("host", "https://voice.example.org/path");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(unsafeHost));

        JSONObject missingDefault = new JSONObject(validConfig());
        missingDefault.getJSONObject("radio").put("defaultChannel", "missing");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(missingDefault));

        JSONObject missingConnection = new JSONObject(validConfig());
        missingConnection.getJSONArray("channels").getJSONObject(0)
                .put("connectionId", "missing");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(missingConnection));

        JSONObject controlCharacterUsername = new JSONObject(validConfig());
        controlCharacterUsername.getJSONObject("connections").getJSONObject("server-a")
                .put("username", "BAD\nNAME");
        assertThrows(JSONException.class,
                () -> RadioConnectionConfig.fromJson(controlCharacterUsername));

        JSONObject nonStringPassword = new JSONObject(validConfig());
        nonStringPassword.getJSONObject("connections").getJSONObject("server-a")
                .put("password", 25);
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(nonStringPassword));

        JSONObject legacySchema = new JSONObject(validConfig());
        legacySchema.put("schemaVersion", 2);
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(legacySchema));
    }

    @Test
    public void normalizesOnlySafeAbsoluteChannelPaths() throws JSONException {
        assertEquals("/PUBLIC/MAIN", RadioConnectionConfig.normalizePath(" /PUBLIC/MAIN/ "));
        assertThrows(JSONException.class, () -> RadioConnectionConfig.normalizePath("PUBLIC/MAIN"));
        assertThrows(JSONException.class, () -> RadioConnectionConfig.normalizePath("/PUBLIC//MAIN"));
        assertEquals("AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
                RadioConnectionConfig.normalizeFingerprint(
                        "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"));
        assertThrows(JSONException.class,
                () -> RadioConnectionConfig.normalizeFingerprint("not-a-fingerprint"));
    }

    @Test
    public void validatesOptionalChannelAlias() throws JSONException {
        JSONObject json = new JSONObject(validConfig());
        JSONObject channel = json.getJSONArray("channels").getJSONObject(0);

        channel.put("alias", "Dispatch");
        assertEquals("Dispatch", RadioConnectionConfig.fromJson(json)
                .getDefaultChannel().getAlias());

        channel.put("alias", " Dispatch " );
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));

        channel.put("alias", "");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));

        channel.put("alias", 25);
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));

        channel.put("alias", "123456789012345678901234567890123");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));

        channel.put("alias", "OPS\nMAIN");
        assertThrows(JSONException.class, () -> RadioConnectionConfig.fromJson(json));
    }

    private static String validConfig() {
        return "{"
                + "\"schemaVersion\":3,\"configVersion\":7,\"deviceId\":\"*\","
                + "\"service\":{\"name\":\"Minimum Test\"},"
                + "\"radio\":{\"defaultChannel\":\"main\",\"autoConnect\":true,"
                + "\"autoReconnect\":false},"
                + "\"connections\":{"
                + "\"server-a\":{\"name\":\"Server A\",\"host\":\"voice-a.example.org\","
                + "\"port\":64738,\"username\":\"E25FGL-T99\","
                + "\"password\":\"server-a-password\",\"serverCertificateSha256\":"
                + "\"AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:"
                + "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\"},"
                + "\"server-b\":{\"name\":\"Server B\",\"host\":\"voice-b.example.org\","
                + "\"port\":64739,\"username\":\"OTHER\","
                + "\"password\":\"server-b-password\"}},"
                + "\"channels\":["
                + "{\"id\":\"main\",\"label\":\"Main\",\"alias\":\"Ops Main\","
                + "\"connectionId\":\"server-a\","
                + "\"path\":\"/PUBLIC/MAIN\",\"presetKey\":\"P1\","
                + "\"access\":{\"mode\":\"public\",\"tokens\":[\" PUBLIC-A \",\"SHARED\"]}},"
                + "{\"id\":\"other\",\"label\":\"Other\",\"connectionId\":\"server-b\","
                + "\"path\":\"/PUBLIC/OTHER\",\"presetKey\":\"P2\","
                + "\"access\":{\"mode\":\"public\",\"token\":\"PUBLIC-B\"}}],"
                + "\"ui\":{\"profile\":\"small-radio\"},"
                + "\"ptt\":{\"maximumTxSeconds\":120,\"releaseOnNetworkLoss\":true},"
                + "\"hardware\":{\"profile\":\"generic-radio\"}"
                + "}";
    }
}
