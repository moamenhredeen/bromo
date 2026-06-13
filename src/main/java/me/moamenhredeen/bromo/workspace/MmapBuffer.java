package me.moamenhredeen.bromo.workspace;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Read-only buffer over a file's contents.
///
/// **v0 implementation**: memory-map the file, decode bytes to a `String` once,
/// hand the string out as a `CharSequence`. The off-heap optimisation
/// (UTF-8 byte index + on-demand char decoding, so source files never enter
/// the GC heap) is scheduled for v0.1 — bench data from M0–M2 will tell us
/// whether it's worth the indexing cost.
public final class MmapBuffer implements CharSequence {

    private final Path path;
    private final String content;

    private MmapBuffer(Path path, String content) {
        this.path = path;
        this.content = content;
    }

    public static MmapBuffer open(Path path) throws IOException {
        long size = Files.size(path);
        if (size == 0) {
            return new MmapBuffer(path, "");
        }
        if (size > Integer.MAX_VALUE) {
            throw new IOException("file too large for v0 MmapBuffer: " + path + " (" + size + " bytes)");
        }
        try (var ch = FileChannel.open(path, StandardOpenOption.READ);
             var arena = Arena.ofConfined()) {
            var segment = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            byte[] bytes = segment.toArray(ValueLayout.JAVA_BYTE);
            return new MmapBuffer(path, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    public Path path() { return path; }

    @Override public int length() { return content.length(); }
    @Override public char charAt(int index) { return content.charAt(index); }
    @Override public CharSequence subSequence(int start, int end) { return content.subSequence(start, end); }
    @Override public String toString() { return content; }
}
