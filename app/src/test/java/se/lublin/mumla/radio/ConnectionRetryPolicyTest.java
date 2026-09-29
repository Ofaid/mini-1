package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import se.lublin.humla.util.ConnectionRetryPolicy;
import se.lublin.humla.util.HumlaException;

public class ConnectionRetryPolicyTest {
    @Test
    public void managedRadioRetriesTransportErrorsButStopsOnServerRejection() {
        assertTrue(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR));
        assertFalse(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.REJECT));
        assertFalse(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.USER_REMOVE));
        assertFalse(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.OTHER_ERROR));
    }

    @Test
    public void standardModeRetainsTransportErrorOnlyPolicy() {
        assertTrue(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR));
        assertFalse(ConnectionRetryPolicy.shouldRetry(true, false,
                HumlaException.HumlaDisconnectReason.REJECT));
        assertFalse(ConnectionRetryPolicy.shouldRetry(false, true,
                HumlaException.HumlaDisconnectReason.CONNECTION_ERROR));
    }

    @Test
    public void retryDelayBacksOffAndCaps() {
        assertTrue(ConnectionRetryPolicy.retryDelayMs(15000L, 0, 60000L) == 15000L);
        assertTrue(ConnectionRetryPolicy.retryDelayMs(15000L, 1, 60000L) == 30000L);
        assertTrue(ConnectionRetryPolicy.retryDelayMs(15000L, 2, 60000L) == 60000L);
        assertTrue(ConnectionRetryPolicy.retryDelayMs(15000L, 10, 60000L) == 60000L);
    }

    @Test
    public void attemptThrottleReturnsOnlyTheRemainingInterval() {
        assertTrue(ConnectionRetryPolicy.remainingAttemptDelayMs(15000L, 100000L, 90000L)
                == 5000L);
        assertTrue(ConnectionRetryPolicy.remainingAttemptDelayMs(15000L, 105000L, 90000L)
                == 0L);
        assertTrue(ConnectionRetryPolicy.remainingAttemptDelayMs(0L, 100000L, 99000L)
                == 0L);
    }
}
