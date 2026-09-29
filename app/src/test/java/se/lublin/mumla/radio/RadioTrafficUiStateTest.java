package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RadioTrafficUiStateTest {
    @Test
    public void returnsToReadyAfterTheLastTalkerStops() {
        RadioTrafficUiState receiving = RadioTrafficUiState.from(
                Collections.singletonList("Remote User"));
        assertEquals(RadioTrafficUiState.Kind.SINGLE_TALKER, receiving.getKind());
        assertEquals("Remote User", receiving.getTalker());

        RadioTrafficUiState ready = RadioTrafficUiState.from(Collections.emptyList());
        assertEquals(RadioTrafficUiState.Kind.READY, ready.getKind());
        assertEquals("", ready.getTalker());
    }

    @Test
    public void distinguishesUnknownAndMultipleTalkers() {
        RadioTrafficUiState unknown = RadioTrafficUiState.from(
                Collections.singletonList(""));
        assertEquals(RadioTrafficUiState.Kind.SINGLE_TALKER, unknown.getKind());
        assertEquals("", unknown.getTalker());

        RadioTrafficUiState multiple = RadioTrafficUiState.from(
                Arrays.asList("A", "B"));
        assertEquals(RadioTrafficUiState.Kind.MULTIPLE_TALKERS, multiple.getKind());
        assertEquals(Arrays.asList("A", "B"), multiple.getTalkers());
    }

    @Test
    public void keepsTalkerSnapshotImmutable() {
        List<String> source = Arrays.asList("A", "B");
        RadioTrafficUiState state = RadioTrafficUiState.from(source);
        try {
            state.getTalkers().add("C");
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("Talker snapshot must be immutable");
    }
}
