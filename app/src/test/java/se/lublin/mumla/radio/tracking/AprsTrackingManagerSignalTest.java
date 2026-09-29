package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AprsTrackingManagerSignalTest {
    @Test
    public void prefersValidVendorDbmForLteWithUnknownGsmAsu() {
        assertEquals(-98, AprsTrackingManager.normalizeSignalDbm(-98, 99));
    }

    @Test
    public void convertsValidGsmAsuWhenVendorDbmIsInvalid() {
        assertEquals(-103, AprsTrackingManager.normalizeSignalDbm(0, 5));
    }

    @Test
    public void rejectsUnknownAndImpossibleValues() {
        assertEquals(AprsHealthSnapshot.UNKNOWN,
                AprsTrackingManager.normalizeSignalDbm(Integer.MAX_VALUE, 99));
        assertEquals(AprsHealthSnapshot.UNKNOWN,
                AprsTrackingManager.normalizeSignalDbm(-160, -1));
    }
}
