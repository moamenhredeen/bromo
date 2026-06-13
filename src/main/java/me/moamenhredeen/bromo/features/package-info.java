/// Paradigm-agnostic feature handlers (hover, definition, completion, …).
///
/// Each feature takes our own param records + a [me.moamenhredeen.bromo.util.CancelToken],
/// returns our own result records synchronously, and blocks on virtual
/// threads. Features **never** import LSP4J or `CompletableFuture` — the
/// adapter layer in [me.moamenhredeen.bromo.lsp] handles all translation.
package me.moamenhredeen.bromo.features;
