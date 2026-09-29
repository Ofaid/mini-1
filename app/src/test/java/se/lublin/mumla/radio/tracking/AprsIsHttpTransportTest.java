package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AprsIsHttpTransportTest {
    @Test
    public void requiresDocumentedPacketReceiptForSuccess() {
        assertEquals(AprsTransport.SendResult.Status.SUCCESS,
                AprsIsHttpTransport.classifyResponse(204, "1").getStatus());
        assertEquals(AprsTransport.SendResult.Status.UNCERTAIN_DELIVERY,
                AprsIsHttpTransport.classifyResponse(204, null).getStatus());
        assertEquals(AprsTransport.SendResult.Status.UNCERTAIN_DELIVERY,
                AprsIsHttpTransport.classifyResponse(204, "0").getStatus());
    }

    @Test
    public void separatesCredentialRejectionFromTransientServerFailure() {
        assertEquals(AprsTransport.SendResult.Status.PERMANENT_FAILURE,
                AprsIsHttpTransport.classifyResponse(403, null).getStatus());
        assertEquals(AprsTransport.SendResult.Status.RETRYABLE_FAILURE,
                AprsIsHttpTransport.classifyResponse(503, null).getStatus());
        assertEquals(AprsTransport.SendResult.Status.RETRYABLE_FAILURE,
                AprsIsHttpTransport.classifyResponse(409, null).getStatus());
    }
}
