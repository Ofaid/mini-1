package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AprsIsTcpTransportTest {
    @Test
    public void validatesSingleAsciiTnc2LineWithinAprsLimit() {
        assertTrue(AprsIsTcpTransport.isValidPacketLine("E25FGL>APRS,TCPIP*:>status"));
        assertFalse(AprsIsTcpTransport.isValidPacketLine("E25FGL>APRS*:line\r\nnext"));
        assertFalse(AprsIsTcpTransport.isValidPacketLine("E25FGL>APRS*:\u0e17\u0e14\u0e2a\u0e2d\u0e1a"));
    }

    @Test
    public void requiresVerifiedResponseForTheConfiguredCallsign() {
        assertEquals(AprsIsTcpTransport.LoginResponse.VERIFIED,
                AprsIsTcpTransport.parseLoginResponse(
                        "# logresp E25FGL verified, server APRS-IS", "E25FGL"));
        assertEquals(AprsIsTcpTransport.LoginResponse.INVALID,
                AprsIsTcpTransport.parseLoginResponse(
                        "# logresp OTHER verified, server APRS-IS", "E25FGL"));
        assertEquals(AprsIsTcpTransport.LoginResponse.REJECTED,
                AprsIsTcpTransport.parseLoginResponse(
                        "# logresp E25FGL unverified, server APRS-IS", "E25FGL"));
        assertEquals(AprsIsTcpTransport.LoginResponse.INVALID,
                AprsIsTcpTransport.parseLoginResponse(
                        "# logresp E25FGL verified", "E25FGL"));
    }
}
