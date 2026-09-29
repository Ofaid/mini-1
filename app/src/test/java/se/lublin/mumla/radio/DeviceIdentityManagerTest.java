package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceIdentityManagerTest {
    @Test
    public void acceptsOnlySixUppercaseAlphanumericCharactersWithLetterAndDigit() {
        assertTrue(DeviceIdentityManager.isValidDeviceId("A7K3Q9"));
        assertTrue(DeviceIdentityManager.isValidDeviceId("4FX8LM"));
        assertTrue(DeviceIdentityManager.isValidDeviceId("GYZ3DE"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("AAAAAA"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("123456"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("a7K3Q9"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("A7K3Q"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("A7K3Q-"));
        assertFalse(DeviceIdentityManager.isValidDeviceId("E25FGL-T99"));
    }

    @Test
    public void detectsKnownAndFutureRadioProfiles() {
        org.junit.Assert.assertEquals(RadioDeviceProfile.T99,
                RadioDeviceProfile.detect("Youdotech", "QM011"));
        org.junit.Assert.assertEquals(RadioDeviceProfile.T56,
                RadioDeviceProfile.detect("UNIPRO", "ZX"));
        org.junit.Assert.assertEquals(RadioDeviceProfile.RYKS,
                RadioDeviceProfile.detect("ELINK", "ym_258"));
        org.junit.Assert.assertEquals(RadioDeviceProfile.GENERIC,
                RadioDeviceProfile.detect("unknown", "phone"));
    }

    @Test
    public void locationTrackingIsAllowedOnlyOnAcceptedHardware() {
        assertTrue(RadioDeviceProfile.supportsLocationTracking(RadioDeviceProfile.T56));
        assertFalse(RadioDeviceProfile.supportsLocationTracking(RadioDeviceProfile.T99));
        assertFalse(RadioDeviceProfile.supportsLocationTracking(RadioDeviceProfile.RYKS));
        assertFalse(RadioDeviceProfile.supportsLocationTracking(RadioDeviceProfile.GENERIC));
        assertFalse(RadioDeviceProfile.supportsLocationTracking(null));
    }
}
