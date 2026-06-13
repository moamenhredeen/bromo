/// LSP wire transport.
///
/// v0 delegates to LSP4J's `LSPLauncher` over stdin/stdout. After the R2
/// replacement trigger fires, this package becomes a hand-rolled NIO
/// reader/writer + streaming JSON codec + sealed-type pattern-match dispatcher.
///
/// Together with [me.moamenhredeen.bromo.lsp] this is one of two packages allowed
/// to import `org.eclipse.lsp4j.*` (enforced by the architecture tests).
package me.moamenhredeen.bromo.wire;
