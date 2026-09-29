package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;

import org.junit.Test;

public class AprsPacketEncoderTest {
    @Test
    public void encodesPositionOwnedBySourceCallsign() {
        TrackingFix fix = fix(13.7563, 100.5018, 8.0f,
                Instant.parse("2026-08-07T09:05:00Z").toEpochMilli(), 1000L,
                TrackingFix.UNKNOWN, TrackingFix.UNKNOWN);

        String packet = AprsPacketEncoder.encodePosition("E25FGL", fix,
                '/', '[', "Minimum T56");

        assertEquals("E25FGL>APRS,TCPIP*:@070905z1345.38N/10030.11E[Minimum T56",
                packet);
        assertFalse(packet.contains(",q"));
    }

    @Test
    public void encodesObjectWithStateSpecificSymbols() {
        TrackingFix fix = fix(13.7563, 100.5018, 8.0f,
                Instant.parse("2026-08-07T09:05:00Z").toEpochMilli(), 1000L,
                TrackingFix.UNKNOWN, TrackingFix.UNKNOWN);

        String packet = AprsPacketEncoder.encodeObject("E25FGL", "VR-A1B2C3", fix,
                '/', '>', "T56 VE");

        assertTrue(packet.contains(";VR-A1B2C3*070905z1345.38N/10030.11E>T56 VE"));
    }

    @Test
    public void encodesConventionalUncompressedObjectWithoutQConstruct() {
        TrackingFix fix = fix(13.7563, 100.5018, 8.0f,
                Instant.parse("2026-08-07T09:05:00Z").toEpochMilli(), 1000L,
                TrackingFix.UNKNOWN, TrackingFix.UNKNOWN);

        String packet = AprsPacketEncoder.encodeObject("E25FGL", "A1B2C3   ", fix,
                '/', '[', "Minimum T56");

        assertEquals("E25FGL>APRS,TCPIP*:;A1B2C3   *070905z1345.38N/10030.11E[Minimum T56",
                packet);
        assertFalse(packet.contains(",q"));
        assertFalse(packet.contains("pass"));
    }

    @Test
    public void addsCourseAndSpeedForMovingFix() {
        TrackingFix fix = fix(-33.865, 151.2094, 6.0f,
                Instant.parse("2026-08-07T10:15:00Z").toEpochMilli(), 2000L,
                10.0f, 90.0f);

        String packet = AprsPacketEncoder.encodeObject("E25FGL", "T56TEST  ", fix,
                '/', '[', "moving");

        assertTrue(packet.contains("3351.90S/15112.56E[090/019moving"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidObjectNameLength() {
        AprsPacketEncoder.encodeObject("E25FGL", "SHORT", fix(0.0, 0.0, 5.0f,
                1L, 1L, TrackingFix.UNKNOWN, TrackingFix.UNKNOWN), '/', '[', "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortOriginCallsign() {
        AprsPacketEncoder.encodeObject("AB", "A1B2C3   ", fix(0.0, 0.0, 5.0f,
                1L, 1L, TrackingFix.UNKNOWN, TrackingFix.UNKNOWN), '/', '[', "");
    }

    private static TrackingFix fix(double latitude, double longitude, float accuracy,
                                   long wallTime, long elapsed, float speed, float bearing) {
        return new TrackingFix(latitude, longitude, accuracy, wallTime, elapsed, speed, bearing);
    }
}
