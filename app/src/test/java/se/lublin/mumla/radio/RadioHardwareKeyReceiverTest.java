package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import se.lublin.mumla.service.MumlaService;

public class RadioHardwareKeyReceiverTest {
    @Test
    public void routesOnlyProfileSpecificPttBroadcasts() {
        assertTrue(RadioHardwareKeyReceiver.isPttDownAction(
                RadioDeviceProfile.T56, RadioHardwareKeyReceiver.ACTION_T56_PTT_DOWN));
        assertTrue(RadioHardwareKeyReceiver.isPttUpAction(
                RadioDeviceProfile.T56, RadioHardwareKeyReceiver.ACTION_T56_PTT_UP));
        assertTrue(RadioHardwareKeyReceiver.isPttDownAction(
                RadioDeviceProfile.RYKS, RadioHardwareKeyReceiver.ACTION_RYKS_PTT_DOWN));
        assertTrue(RadioHardwareKeyReceiver.isPttUpAction(
                RadioDeviceProfile.RYKS, RadioHardwareKeyReceiver.ACTION_RYKS_PTT_UP));

        assertFalse(RadioHardwareKeyReceiver.isPttDownAction(
                RadioDeviceProfile.RYKS, RadioHardwareKeyReceiver.ACTION_T56_PTT_DOWN));
        assertFalse(RadioHardwareKeyReceiver.isPttDownAction(
                RadioDeviceProfile.T99, RadioHardwareKeyReceiver.ACTION_RYKS_PTT_DOWN));
    }

    @Test
    public void runningServiceDispatchDoesNotQueueWhenServiceIsAbsent() {
        assertFalse(MumlaService.dispatchRadioPttAction(MumlaService.ACTION_RADIO_PTT_DOWN));
        assertFalse(MumlaService.dispatchRadioPttAction(MumlaService.ACTION_RADIO_PTT_UP));
        assertFalse(MumlaService.dispatchRadioPttAction("unrelated-action"));
    }
}
