package se.lublin.mumla.radio;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class RadioTalkerDisplayTest {
    @Test
    public void formatsOneTalkerOnOneLine() {
        RadioTalkerDisplay.Display display = RadioTalkerDisplay.format(
                Collections.singletonList("Remote User"), 2, "+0");

        assertEquals("Remote User", display.getText());
        assertEquals("Remote User", display.getAccessibilityText());
        assertEquals(0, display.getHiddenCount());
    }

    @Test
    public void formatsMultipleTalkersOnSeparateLines() {
        RadioTalkerDisplay.Display display = RadioTalkerDisplay.format(
                Arrays.asList("Alice", "Bob"), 2, "+0");

        assertEquals("Alice\nBob", display.getText());
        assertEquals(2, display.getVisibleCount());
    }

    @Test
    public void addsOverflowCountWithoutDroppingAccessibleNames() {
        RadioTalkerDisplay.Display display = RadioTalkerDisplay.format(
                Arrays.asList("Alice", "Bob", "Carol", "Dave"), 2, "+2");

        assertEquals("Alice\nBob +2", display.getText());
        assertEquals("Alice\nBob\nCarol\nDave\n+2", display.getAccessibilityText());
        assertEquals(2, display.getHiddenCount());
    }

    @Test
    public void normalizesEmbeddedLineBreaksAndNullNames() {
        RadioTalkerDisplay.Display display = RadioTalkerDisplay.format(
                Arrays.asList(" A\nB ", null), 2, "+0");

        assertEquals("A B\n", display.getText());
    }
}
