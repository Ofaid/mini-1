package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class AprsObjectNameTest {
    @Test
    public void sixCharacterDeviceIdGetsWildcardFriendlyVrPrefix() {
        assertEquals("VR-A1B2C3", AprsObjectName.fromDeviceId("a1b2c3"));
    }

    @Test
    public void configuredNameIsUppercasedAndPaddedToAprsLength() {
        assertEquals("T56-ROOF ", AprsObjectName.fromConfiguredName("t56-roof"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void configuredNameRejectsLeadingPunctuation() {
        AprsObjectName.fromConfiguredName("/T56");
    }

    @Test(expected = IllegalArgumentException.class)
    public void configuredNameRejectsOuterWhitespace() {
        AprsObjectName.fromConfiguredName("T56 ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void configuredNameRejectsMoreThanNineCharacters() {
        AprsObjectName.fromConfiguredName("T56-ROOF-1");
    }

    @Test
    public void fallbackMappingIsStableRecognizableAndLowCollision() {
        String first = AprsObjectName.fromDeviceId("radio-alpha-long");
        String repeated = AprsObjectName.fromDeviceId("radio-alpha-long");
        String second = AprsObjectName.fromDeviceId("radio-alpha-other");

        assertEquals(9, first.length());
        assertEquals(first, repeated);
        assertEquals("VR-", first.substring(0, 3));
        assertNotEquals(first, second);
    }
}
