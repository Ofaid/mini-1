package se.lublin.mumla.radio.tracking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class TrackingAttemptGateTest {
    @Test
    public void reconfigureInvalidatesOldAttemptsUntilLatestConfigIsApplied() {
        TrackingAttemptGate gate = new TrackingAttemptGate();
        long oldAttempt = gate.beginAttempt();
        long oldTicket = gate.beginReconfigure();
        long latestTicket = gate.beginReconfigure();
        AtomicInteger commits = new AtomicInteger();

        assertFalse(gate.runIfCurrent(oldAttempt, commits::incrementAndGet));
        assertFalse(gate.applyReconfiguration(oldTicket, true, commits::incrementAndGet));
        assertEquals(TrackingAttemptGate.NO_ATTEMPT, gate.beginAttempt());
        assertTrue(gate.applyReconfiguration(latestTicket, true, commits::incrementAndGet));

        long currentAttempt = gate.beginAttempt();
        assertNotEquals(TrackingAttemptGate.NO_ATTEMPT, currentAttempt);
        assertTrue(gate.runIfCurrent(currentAttempt, commits::incrementAndGet));
        assertEquals(2, commits.get());
    }

    @Test
    public void disabledConfigAndStopRejectResults() {
        TrackingAttemptGate gate = new TrackingAttemptGate();
        long firstAttempt = gate.beginAttempt();
        long ticket = gate.beginReconfigure();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(gate.applyReconfiguration(ticket, false, commits::incrementAndGet));
        assertEquals(TrackingAttemptGate.NO_ATTEMPT, gate.beginAttempt());
        assertFalse(gate.runIfCurrent(firstAttempt, commits::incrementAndGet));

        gate.stop();
        assertEquals(TrackingAttemptGate.NO_ATTEMPT, gate.beginAttempt());
        assertEquals(1, commits.get());
    }
}
