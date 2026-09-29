package se.lublin.mumla.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;

public class RadioExternalTalkPolicyTest {
    @Test
    public void onAndToggleStartOnlyWhenReadyAndReleased() {
        assertEquals(RadioExternalTalkPolicy.Decision.START,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_ON,
                        false, true, false));
        assertEquals(RadioExternalTalkPolicy.Decision.START,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_TOGGLE,
                        false, true, false));
        assertEquals(RadioExternalTalkPolicy.Decision.REJECT,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_ON,
                        false, false, false));
        assertEquals(RadioExternalTalkPolicy.Decision.REJECT,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_ON,
                        false, true, true));
    }

    @Test
    public void repeatedOnKeepsOriginalWatchdogDeadline() {
        assertEquals(RadioExternalTalkPolicy.Decision.KEEP,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_ON,
                        true, true, false));
    }

    @Test
    public void offIsAlwaysAReleaseAndToggleStopsActiveTransmission() {
        assertEquals(RadioExternalTalkPolicy.Decision.STOP,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_OFF,
                        false, false, true));
        assertEquals(RadioExternalTalkPolicy.Decision.STOP,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_TOGGLE,
                        true, true, false));
        assertEquals(RadioExternalTalkPolicy.Decision.STOP,
                RadioExternalTalkPolicy.decide(TalkBroadcastReceiver.TALK_STATUS_TOGGLE,
                        false, true, true));
    }

    @Test
    public void unknownStatusIsIgnored() {
        assertEquals(RadioExternalTalkPolicy.Decision.IGNORE,
                RadioExternalTalkPolicy.decide("invalid", false, true, false));
    }
}
