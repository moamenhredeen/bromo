/// Tier-2 compiler — ECJ-backed attribution, diagnostics, and (later) cursor-context engines.
///
/// Drives the long-lived `org.eclipse.jdt.internal.compiler.Compiler` against
/// the project's source tree, using overlays from the open documents in
/// [com.almato.bromo.workspace.FileStore]. The output is a flow of
/// [Diagnostic] records that the LSP layer publishes via
/// `textDocument/publishDiagnostics`.
///
/// Cursor-context engines (`CompletionEngine`, `SelectionEngine`) land at
/// M6/M7 as features come online.
package com.almato.bromo.compiler;
