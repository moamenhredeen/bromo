/// LSP4J adapter layer.
///
/// This is the **only** package in the codebase (alongside [me.moamenhredeen.bromo.wire])
/// permitted to import `org.eclipse.lsp4j.*` and `java.util.concurrent.CompletableFuture`.
/// Service-impl classes here translate LSP4J protocol types into bromo's own
/// param/result records and dispatch to feature handlers on virtual threads.
///
/// Feature handlers (`me.moamenhredeen.bromo.features.*`) remain paradigm-agnostic:
/// they take param records + a [me.moamenhredeen.bromo.util.CancelToken] and return
/// result records synchronously, blocking on virtual threads. The boundary
/// is enforced by the architecture tests in `arch/`.
package me.moamenhredeen.bromo.lsp;
