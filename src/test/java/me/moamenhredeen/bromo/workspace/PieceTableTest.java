package me.moamenhredeen.bromo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PieceTableTest {

    @Test
    @DisplayName("empty document has zero length and empty text")
    void empty() {
        var pt = new PieceTable("");
        assertEquals(0, pt.length());
        assertEquals("", pt.text());
    }

    @Test
    @DisplayName("initial content is round-tripped")
    void initialRoundTrip() {
        var pt = new PieceTable("hello");
        assertEquals(5, pt.length());
        assertEquals("hello", pt.text());
        assertEquals('e', pt.charAt(1));
    }

    @Test
    @DisplayName("insert at start prepends")
    void insertAtStart() {
        var pt = new PieceTable("world");
        pt.insert(0, "hello ");
        assertEquals("hello world", pt.text());
        assertEquals(11, pt.length());
    }

    @Test
    @DisplayName("insert at end appends")
    void insertAtEnd() {
        var pt = new PieceTable("hello");
        pt.insert(5, " world");
        assertEquals("hello world", pt.text());
    }

    @Test
    @DisplayName("insert in the middle splits a piece")
    void insertInMiddle() {
        var pt = new PieceTable("helo");
        pt.insert(2, "l");
        assertEquals("hello", pt.text());
    }

    @Test
    @DisplayName("delete at start trims prefix")
    void deleteAtStart() {
        var pt = new PieceTable("hello world");
        pt.delete(0, 6);
        assertEquals("world", pt.text());
    }

    @Test
    @DisplayName("delete at end trims suffix")
    void deleteAtEnd() {
        var pt = new PieceTable("hello world");
        pt.delete(5, 6);
        assertEquals("hello", pt.text());
    }

    @Test
    @DisplayName("delete in middle removes a slice")
    void deleteInMiddle() {
        var pt = new PieceTable("hello brave world");
        pt.delete(5, 6);
        assertEquals("hello world", pt.text());
    }

    @Test
    @DisplayName("delete spanning multiple pieces works")
    void deleteAcrossPieces() {
        var pt = new PieceTable("abc");
        pt.insert(1, "XY");       // aXYbc
        pt.insert(4, "Z");        // aXYbZc
        pt.delete(1, 4);          // ac
        assertEquals("ac", pt.text());
    }

    @Test
    @DisplayName("replace combines delete + insert")
    void replace() {
        var pt = new PieceTable("hello world");
        pt.replace(6, 5, "java");
        assertEquals("hello java", pt.text());
    }

    @Test
    @DisplayName("subSequence crosses piece boundaries")
    void subSequenceCrossesPieces() {
        var pt = new PieceTable("abc");
        pt.insert(1, "XYZ");      // aXYZbc
        assertEquals("XYZb", pt.subSequence(1, 5).toString());
    }

    @Test
    @DisplayName("charAt walks pieces correctly")
    void charAtAcrossPieces() {
        var pt = new PieceTable("abc");
        pt.insert(1, "X");
        assertEquals('a', pt.charAt(0));
        assertEquals('X', pt.charAt(1));
        assertEquals('b', pt.charAt(2));
        assertEquals('c', pt.charAt(3));
    }

    @Test
    @DisplayName("invalid offsets throw")
    void invalidOffsets() {
        var pt = new PieceTable("hello");
        assertThrows(IndexOutOfBoundsException.class, () -> pt.insert(-1, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> pt.insert(6, "x"));
        assertThrows(IndexOutOfBoundsException.class, () -> pt.delete(0, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> pt.charAt(5));
    }

    @Test
    @DisplayName("multi-line content with newlines round-trips")
    void multiline() {
        var src = "package foo;\n\nclass Foo {}\n";
        var pt = new PieceTable(src);
        pt.insert(8, "bar.");
        assertEquals("package bar.foo;\n\nclass Foo {}\n", pt.text());
    }
}
