package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import se.lublin.humla.model.TalkState;

public class RadioReceiveTrackerTest {
    @Test
    public void tracksEveryAudibleRemoteStateUntilAllSessionsStop() {
        RadioReceiveTracker tracker = new RadioReceiveTracker();

        tracker.update(10, "A", false, TalkState.TALKING);
        tracker.update(11, "B", false, TalkState.WHISPERING);
        tracker.update(12, "C", false, TalkState.SHOUTING);
        assertTrue(tracker.isReceiving());
        assertTrue(tracker.getActiveTalkers().contains("B"));

        tracker.update(10, "A", false, TalkState.PASSIVE);
        tracker.remove(11);
        assertTrue(tracker.isReceiving());

        tracker.update(12, "C", false, TalkState.PASSIVE);
        assertFalse(tracker.isReceiving());
    }

    @Test
    public void ignoresSelfAndClearsOnDisconnect() {
        RadioReceiveTracker tracker = new RadioReceiveTracker();

        tracker.update(20, "Self", true, TalkState.TALKING);
        assertFalse(tracker.isReceiving());

        tracker.update(21, "Remote", false, TalkState.TALKING);
        tracker.clear();
        assertFalse(tracker.isReceiving());
    }
}
