package com.samvaad.tui.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class E2eePayloadTest {

    @Test
    void encodesVersionedFraming() {
        assertArrayEquals(new byte[] {1, 1, 'h', 'i'}, E2eePayload.encodeDirectText("hi"));
    }

    @Test
    void roundTripsMultibyteText() {
        String text = "héllo ✓";
        E2eePayload.DecodedPayload decoded = E2eePayload.decode(E2eePayload.encodeDirectText(text));
        assertEquals(1, decoded.version());
        assertEquals(1, decoded.type());
        assertEquals(text, decoded.content());
    }

    @Test
    void rejectsTruncatedInput() {
        assertThrows(E2eeException.class, () -> E2eePayload.decode(new byte[] {1}));
        assertThrows(E2eeException.class, () -> E2eePayload.decode(new byte[0]));
    }

    @Test
    void rejectsUnknownVersion() {
        assertThrows(E2eeException.class, () -> E2eePayload.decode(new byte[] {2, 1, 'x'}));
    }

    @Test
    void rejectsUnknownType() {
        assertThrows(E2eeException.class, () -> E2eePayload.decode(new byte[] {1, 9, 'x'}));
    }
}
