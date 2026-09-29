package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import se.lublin.mumla.radio.RadioDeviceProfile;

public class AprsTrackingConfigTest {
    @Test
    public void trackingIsHardDisabledOutsideT56() throws Exception {
        AprsTrackingConfig config = AprsTrackingConfig.fromJson(enabledConfig(),
                RadioDeviceProfile.T99);
        assertFalse(config.isEnabled());
        assertFalse(config.isAprsEnabled());
    }

    @Test
    public void parsesMinimalT56AprsConfiguration() throws Exception {
        AprsTrackingConfig config = AprsTrackingConfig.fromJson(enabledConfig(),
                RadioDeviceProfile.T56);
        assertTrue(config.isEnabled());
        assertTrue(config.isAprsEnabled());
        assertTrue(config.isPttTriggered());
        assertEquals("E25FGL", config.getSourceCallsign());
        assertEquals(AprsTrackingConfig.DEFAULT_HOST, config.getHost());
        assertEquals(AprsTrackingConfig.DEFAULT_PORT, config.getPort());
        assertEquals("", config.getObjectName());
    }

    @Test
    public void parsesOptionalConfiguredObjectName() throws Exception {
        JSONObject configJson = enabledConfig();
        configJson.getJSONObject("tracking").getJSONObject("aprs")
                .put("objectName", "t56-roof");

        AprsTrackingConfig config = AprsTrackingConfig.fromJson(configJson, RadioDeviceProfile.T56);

        assertEquals("T56-ROOF ", config.getObjectName());
    }

    @Test
    public void malformedConfigFallsBackToDisabledTracking() throws Exception {
        JSONObject configJson = enabledConfig();
        configJson.getJSONObject("tracking").getJSONObject("aprs").remove("passcode");

        AprsTrackingConfig config = AprsTrackingManager.parseConfigOrDisabled(
                configJson, RadioDeviceProfile.T56);

        assertFalse(config.isEnabled());
        assertFalse(config.isAprsEnabled());
    }

    @Test(expected = JSONException.class)
    public void rejectsInvalidConfiguredObjectName() throws Exception {
        JSONObject config = enabledConfig();
        config.getJSONObject("tracking").getJSONObject("aprs")
                .put("objectName", "T56/ROOF");
        AprsTrackingConfig.fromJson(config, RadioDeviceProfile.T56);
    }

    @Test(expected = JSONException.class)
    public void rejectsMissingCredentialWithoutExposingIt() throws Exception {
        JSONObject config = enabledConfig();
        config.getJSONObject("tracking").getJSONObject("aprs").remove("passcode");
        AprsTrackingConfig.fromJson(config, RadioDeviceProfile.T56);
    }

    @Test(expected = JSONException.class)
    public void rejectsOriginCallsignShorterThanAprsMinimum() throws Exception {
        JSONObject config = enabledConfig();
        config.getJSONObject("tracking").getJSONObject("aprs")
                .put("sourceCallsign", "AB");
        AprsTrackingConfig.fromJson(config, RadioDeviceProfile.T56);
    }

    private static JSONObject enabledConfig() throws JSONException {
        return new JSONObject()
                .put("tracking", new JSONObject()
                        .put("enabled", true)
                        .put("pttTriggered", true)
                        .put("aprs", new JSONObject()
                                .put("enabled", true)
                                .put("sourceCallsign", "E25FGL")
                                .put("passcode", "12345")));
    }
}
