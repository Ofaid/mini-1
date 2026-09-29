package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AprsHealthSnapshotTest {
    @Test
    public void formatsCompactHealthCommentWithChargingAndSignals() {
        AprsHealthSnapshot snapshot = new AprsHealthSnapshot(
                87, true, 321, -52, "4G", -103, 12L * 1024L);

        String comment = snapshot.toAprsComment(AprsBeaconCoordinator.MovementState.STATIONARY,
                8.0f);

        assertEquals("T56 ST A8 B87%+ T32.1 W-52 4G-103 S12G", comment);
        assertTrue(comment.length() <= 40);
    }

    @Test
    public void omitsUnavailableHealthFields() {
        AprsHealthSnapshot snapshot = new AprsHealthSnapshot(
                AprsHealthSnapshot.UNKNOWN, false, AprsHealthSnapshot.UNKNOWN,
                AprsHealthSnapshot.UNKNOWN, "", AprsHealthSnapshot.UNKNOWN,
                AprsHealthSnapshot.UNKNOWN);

        assertEquals("T56 VE MNA", snapshot.toAprsComment(AprsBeaconCoordinator.MovementState.VEHICLE));
    }

    @Test
    public void mapsLegacyMobileNetworkTypes() {
        assertEquals("4G", AprsHealthSnapshot.networkTypeCode(
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE));
        assertEquals("3G", AprsHealthSnapshot.networkTypeCode(
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA));
        assertEquals("2G", AprsHealthSnapshot.networkTypeCode(
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE));
    }
}
