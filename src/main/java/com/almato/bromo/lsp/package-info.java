/// LSP4J adapter layer.
///
/// This is the **only** package in the codebase (alongside [com.almato.bromo.wire])
/// permitted to import `org.eclipse.lsp4j.*` and `java.util.concurrent.CompletableFuture`.
/// Service-impl classes here translate LSP4J protocol types into bromo's own
/// param/result records and dispatch to feature handlers on virtual threads.
///
/// Feature handlers (`com.almato.bromo.features.*`) remain paradigm-agnostic:
/// they take param records + a [com.almato.bromo.util.CancelToken] and return
/// result records synchronously, blocking on virtual threads. The boundary
/// is enforced by the architecture tests in `arch/`.
package com.almato.bromo.lsp;
