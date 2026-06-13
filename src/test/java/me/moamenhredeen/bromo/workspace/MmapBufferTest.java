package me.moamenhredeen.bromo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MmapBufferTest {

    @Test
    @DisplayName("reads a UTF-8 file with non-ASCII content")
    void readsUtf8File(@TempDir Path tmp) throws IOException {
        var path = tmp.resolve("greet.txt");
        Files.writeString(path, "héllo\nwörld", StandardCharsets.UTF_8);
        var buf = MmapBuffer.open(path);
        assertEquals("héllo\nwörld", buf.toString());
        assertEquals(11, buf.length());
    }

    @Test
    @DisplayName("empty file → length 0")
    void emptyFile(@TempDir Path tmp) throws IOException {
        var path = tmp.resolve("empty.txt");
        Files.writeString(path, "", StandardCharsets.UTF_8);
        var buf = MmapBuffer.open(path);
        assertEquals(0, buf.length());
        assertEquals("", buf.toString());
    }

    @Test
    @DisplayName("charAt and subSequence behave like String")
    void charAtAndSubSequence(@TempDir Path tmp) throws IOException {
        var path = tmp.resolve("F.java");
        var content = "class F {}\n";
        Files.writeString(path, content, StandardCharsets.UTF_8);
        var buf = MmapBuffer.open(path);
        assertEquals('c', buf.charAt(0));
        assertEquals("class", buf.subSequence(0, 5).toString());
    }
}
