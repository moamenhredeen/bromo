package me.moamenhredeen.bromo.workspace;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/// LSP URI ↔ filesystem [Path] helpers.
public final class Uris {
    private Uris() {}

    /// Parse an LSP URI string. Throws [IllegalArgumentException] on bad input.
    public static URI parse(String uri) {
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid URI: " + uri, e);
        }
    }

    /// Convert a `file:` URI to a filesystem [Path]. Throws on non-`file:` schemes.
    public static Path toPath(URI uri) {
        var scheme = uri.getScheme();
        if (scheme == null || !"file".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("expected file: URI, got " + scheme + " for " + uri);
        }
        return Paths.get(uri);
    }

    /// Convert a filesystem [Path] to a `file:` URI.
    public static URI fromPath(Path path) {
        return path.toUri();
    }
}
