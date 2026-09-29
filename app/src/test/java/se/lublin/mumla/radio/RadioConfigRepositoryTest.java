package se.lublin.mumla.radio;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class RadioConfigRepositoryTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rejectsOnlyOlderConfigVersions() throws JSONException {
        JSONObject active = new JSONObject("{\"configVersion\":12}");

        assertThrows(JSONException.class, () -> RadioConfigRepository.rejectDowngrade(
                new JSONObject("{\"configVersion\":11}"), active));
        RadioConfigRepository.rejectDowngrade(
                new JSONObject("{\"configVersion\":12}"), active);
        RadioConfigRepository.rejectDowngrade(
                new JSONObject("{\"configVersion\":13}"), active);
    }

    @Test
    public void deviceConfigUsesFixedPerDeviceEndpoint() {
        assertEquals(RadioConfigRepository.DEVICE_CONFIG_BASE_URL + "A1B2C3",
                RadioConfigRepository.deviceConfigUrl("A1B2C3"));
        assertThrows(IllegalArgumentException.class,
                () -> RadioConfigRepository.deviceConfigUrl("../../other"));
    }

    @Test
    public void completeValidationRejectsMalformedChannelBeforePersistence()
            throws JSONException {
        JSONObject malformed = completeTrackingConfig();
        malformed.getJSONObject("connections").getJSONObject("main").remove("host");

        assertThrows(JSONException.class,
                () -> RadioConfigRepository.validateCompleteConfig(malformed, "A1B2C3"));
    }

    @Test
    public void promotesPendingAndCanExplicitlyRollback() throws IOException {
        File directory = temporaryFolder.newFolder("radio-config");
        File active = write(directory, "active-config.json", "active-v1");
        File previous = write(directory, "previous-config.json", "obsolete");
        File pending = write(directory, "pending-config.json", "candidate-v2");

        RadioConfigRepository.promotePendingFiles(directory);

        assertEquals("candidate-v2", read(active));
        assertEquals("active-v1", read(previous));
        assertFalse(pending.exists());
        assertFalse(new File(directory, "previous-config.backup").exists());

        RadioConfigRepository.rollbackFiles(directory);

        assertEquals("active-v1", read(active));
        assertFalse(previous.exists());
        assertFalse(new File(directory, "rollback-config.tmp").exists());
    }

    @Test
    public void promotionWithoutPendingCannotDamageActive() throws IOException {
        File directory = temporaryFolder.newFolder("missing-pending");
        File active = write(directory, "active-config.json", "stable");

        assertThrows(IOException.class,
                () -> RadioConfigRepository.promotePendingFiles(directory));
        assertTrue(active.isFile());
        assertEquals("stable", read(active));
    }

    @Test
    public void provisionedInstallPreservesActiveAsPrevious() throws IOException {
        File directory = temporaryFolder.newFolder("provisioned");
        File active = write(directory, "active-config.json", "active-v1");

        RadioConfigRepository.installProvisionedFiles(
                directory, "provisioned-v2".getBytes(StandardCharsets.UTF_8));

        assertEquals("provisioned-v2", read(active));
        assertEquals("active-v1", read(new File(directory, "previous-config.json")));
        assertFalse(new File(directory, "pending-config.json").exists());
        assertFalse(new File(directory, "provisioned-config.tmp").exists());
    }

    @Test
    public void deviceProfileOverlayCanOverrideOneConnectionUsername()
            throws JSONException {
        JSONObject base = new JSONObject("{"
                + "\"schemaVersion\":3,\"configVersion\":4,\"deviceId\":\"*\","
                + "\"connections\":{\"public-main\":{\"username\":\"MINIMUM\"},"
                + "\"backup\":{\"username\":\"BACKUP\"}}}");
        JSONObject profile = new JSONObject("{"
                + "\"schemaVersion\":3,\"configVersion\":4,"
                + "\"deviceId\":\"GYZ3DE\","
                + "\"connections\":{\"public-main\":{\"username\":\"E25FGL-T99\"}}}");

        RadioConfigRepository.validateOverlay(profile, "GYZ3DE");
        JSONObject merged = RadioConfigRepository.merge(base, profile);

        assertEquals("GYZ3DE", merged.getString("deviceId"));
        assertEquals("E25FGL-T99",
                merged.getJSONObject("connections").getJSONObject("public-main")
                        .getString("username"));
        assertEquals("BACKUP", merged.getJSONObject("connections").getJSONObject("backup")
                .getString("username"));
    }

    @Test
    public void aprsObjectNameUpdateAdvancesVersionAndPreservesPrivateConfig()
            throws JSONException {
        JSONObject current = completeTrackingConfig();

        JSONObject updated = RadioConfigRepository.withAprsObjectName(
                current, "A1B2C3", "hs3hp");

        assertEquals(8, updated.getInt("configVersion"));
        assertEquals("HS3HP", updated.getJSONObject("tracking").getJSONObject("aprs")
                .getString("objectName"));
        assertEquals("12345", updated.getJSONObject("tracking").getJSONObject("aprs")
                .getString("passcode"));
        assertFalse(current.getJSONObject("tracking").getJSONObject("aprs")
                .has("objectName"));
    }

    @Test
    public void aprsObjectNameNoOpDoesNotAdvanceVersion() throws JSONException {
        JSONObject current = completeTrackingConfig();
        current.getJSONObject("tracking").getJSONObject("aprs")
                .put("objectName", "HS3HP");

        JSONObject updated = RadioConfigRepository.withAprsObjectName(
                current, "A1B2C3", "HS3HP");

        assertEquals(7, updated.getInt("configVersion"));
    }

    private static JSONObject completeTrackingConfig() throws JSONException {
        return new JSONObject("{"
                + "\"schemaVersion\":3,\"configVersion\":7,\"deviceId\":\"A1B2C3\","
                + "\"radio\":{\"defaultChannel\":\"main\",\"autoConnect\":true,"
                + "\"autoReconnect\":true},"
                + "\"connections\":{\"main\":{\"host\":\"voice.example.invalid\","
                + "\"port\":64738,\"username\":\"RADIO\"}},"
                + "\"channels\":[{\"id\":\"main\",\"label\":\"Main\","
                + "\"connectionId\":\"main\",\"path\":\"/MAIN\","
                + "\"access\":{\"mode\":\"none\"}}],"
                + "\"ui\":{},\"ptt\":{\"maximumTxSeconds\":120,"
                + "\"releaseOnNetworkLoss\":true},"
                + "\"hardware\":{\"profile\":\"t56\"},"
                + "\"tracking\":{\"enabled\":true,\"aprs\":{\"enabled\":true,"
                + "\"sourceCallsign\":\"N0CALL\",\"passcode\":\"12345\"}}}");
    }

    private static File write(File directory, String name, String value) throws IOException {
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
