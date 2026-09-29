package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RadioProvisionReceiverTest {
    @Test
    public void snapshotDigestIsStableAndDoesNotExposeInput() throws Exception {
        assertEquals(
                "3200E947DB45B2FF41CF51B02139F2F97130112557D9946659A9BC1952A7FDCE",
                RadioProvisionReceiver.sha256("managed-safe-state"));
    }
}
