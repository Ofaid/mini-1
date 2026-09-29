package se.lublin.mumla.radio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class RadioKeyDiagnosticsTest {
    @Test
    public void keepsNewestCompleteRecordsWithoutOverflow() {
        byte[] result = RadioKeyDiagnostics.appendRecord(
                bytes("first\nsecond\n"), bytes("third\n"), 13);

        assertArrayEquals(bytes("second\nthird\n"), result);
    }

    @Test
    public void rejectsOneRecordLargerThanTheBound() {
        assertNull(RadioKeyDiagnostics.appendRecord(bytes("old\n"), bytes("12345\n"), 5));
    }

    @Test
    public void preservesAllRecordsWhenTheyFit() {
        assertArrayEquals(bytes("first\nsecond\n"), RadioKeyDiagnostics.appendRecord(
                bytes("first\n"), bytes("second\n"), 20));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
