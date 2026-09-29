package se.lublin.mumla.radio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RadioNotificationPolicyTest {
    @Test
    public void managedRadioSuppressesChatNotification() {
        assertFalse(RadioNotificationPolicy.shouldShowChatNotification(true, true));
    }

    @Test
    public void standardClientRetainsChatNotificationSetting() {
        assertTrue(RadioNotificationPolicy.shouldShowChatNotification(true, false));
        assertFalse(RadioNotificationPolicy.shouldShowChatNotification(false, false));
    }
}
