package se.lublin.mumla.service;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class MumlaServiceMessageLogTest {
    @Test
    public void retainsOnlyNewestMessages() {
        List<IChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < MumlaService.MAX_MESSAGE_LOG_ENTRIES + 2; index++) {
            MumlaService.appendMessageLog(messages,
                    new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO,
                            "message-" + index));
        }

        assertEquals(MumlaService.MAX_MESSAGE_LOG_ENTRIES, messages.size());
        assertEquals("message-2", messages.get(0).getBody());
        assertEquals("message-" + (MumlaService.MAX_MESSAGE_LOG_ENTRIES + 1),
                messages.get(messages.size() - 1).getBody());
    }

    @Test
    public void ignoresNullHistoryInputs() {
        List<IChatMessage> messages = new ArrayList<>();
        MumlaService.appendMessageLog(messages, null);
        MumlaService.appendMessageLog(null, new IChatMessage.InfoMessage(
                IChatMessage.InfoMessage.Type.INFO, "ignored"));

        assertEquals(0, messages.size());
    }
}
