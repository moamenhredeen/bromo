package me.moamenhredeen.bromo.workspace;

/// LSP position (line, character) ↔ flat character offset conversion.
///
/// LSP 3.17 uses UTF-16 code units for the `character` field, which matches
/// Java's `char` / `CharSequence` natively. No transcoding required.
///
/// Line endings: `\n` ends a line. `\r\n` and `\r` are tolerated by treating
/// `\n` as the terminator; the `\r` belongs to the preceding line.
public final class Positions {
    private Positions() {}

    /// Translate an LSP (line, character) position into a flat character offset.
    /// Clamps past-EOF positions to `content.length()` so callers don't see
    /// `IndexOutOfBoundsException` for edits that describe a column past the
    /// line's end (legitimate per LSP).
    public static int positionToOffset(CharSequence content, int line, int character) {
        int len = content.length();
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < len) {
            if (content.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return Math.min(len, offset + character);
    }

    /// Translate a flat character offset into an LSP (line, character) position.
    /// Clamps past-EOF offsets to the document's end position.
    public static LineCol offsetToPosition(CharSequence content, int offset) {
        int safe = Math.min(offset, content.length());
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safe; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new LineCol(line, safe - lineStart);
    }

    public record LineCol(int line, int character) {}
}
