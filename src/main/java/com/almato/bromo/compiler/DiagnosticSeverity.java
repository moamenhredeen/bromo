package com.almato.bromo.compiler;

/// Severity of a [Diagnostic].
///
/// Mirrors the LSP `DiagnosticSeverity` enum but kept independent so
/// protocol types don't leak past the adapter layer.
public enum DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT,
}
