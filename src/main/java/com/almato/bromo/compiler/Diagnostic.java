package com.almato.bromo.compiler;

import java.net.URI;

/// A single diagnostic emitted by the compiler.
///
/// Offsets are absolute character positions into the source content. The
/// adapter layer translates them to LSP `Range` (line/col) via
/// [com.almato.bromo.workspace.Positions] when answering requests.
///
/// `code` is the stringified ECJ problem id (e.g. `"1610613332"`); reserved
/// for future use as a stable diagnostic identifier in the wire protocol.
public record Diagnostic(
        URI uri,
        DiagnosticSeverity severity,
        int startOffset,
        int endOffset,
        String message,
        String code) {

    public Diagnostic {
        if (uri == null)      throw new IllegalArgumentException("uri");
        if (severity == null) throw new IllegalArgumentException("severity");
        if (message == null)  throw new IllegalArgumentException("message");
        if (code == null)     throw new IllegalArgumentException("code");
    }
}
