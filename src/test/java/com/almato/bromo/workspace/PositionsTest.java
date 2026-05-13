package com.almato.bromo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PositionsTest {

    @Test
    @DisplayName("first line, first column → offset 0")
    void firstLineFirstColumn() {
        assertEquals(0, Positions.positionToOffset("hello\nworld", 0, 0));
    }

    @Test
    @DisplayName("first line, mid column → offset")
    void firstLineSomeColumn() {
        assertEquals(3, Positions.positionToOffset("hello\nworld", 0, 3));
    }

    @Test
    @DisplayName("second line, first column → after the newline")
    void secondLineFirstColumn() {
        assertEquals(6, Positions.positionToOffset("hello\nworld", 1, 0));
    }

    @Test
    @DisplayName("second line, mid column → offset")
    void secondLineSomeColumn() {
        assertEquals(9, Positions.positionToOffset("hello\nworld", 1, 3));
    }

    @Test
    @DisplayName("past-EOF position clamps to content length")
    void pastEofClampsToLength() {
        assertEquals(5, Positions.positionToOffset("hello", 99, 99));
    }

    @Test
    @DisplayName("CRLF: \\n is the line terminator; \\r belongs to the preceding line")
    void crlfTreatsLfAsLineEnd() {
        assertEquals(4, Positions.positionToOffset("hi\r\nthere", 1, 0));
    }

    @Test
    @DisplayName("offset → position round-trips through positionToOffset")
    void roundTrip() {
        var s = "alpha\nbeta\ngamma";
        for (int off = 0; off <= s.length(); off++) {
            var lc = Positions.offsetToPosition(s, off);
            assertEquals(off,
                    Positions.positionToOffset(s, lc.line(), lc.character()),
                    "round-trip at offset " + off);
        }
    }

    @Test
    @DisplayName("empty content always returns offset 0")
    void emptyContent() {
        assertEquals(0, Positions.positionToOffset("", 0, 0));
        assertEquals(0, Positions.positionToOffset("", 99, 99));
    }
}
