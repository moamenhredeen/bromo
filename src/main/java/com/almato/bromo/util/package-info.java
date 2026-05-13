/// Cross-cutting utilities.
///
/// Contents:
/// - [Logging] — `System.Logger` wrapper with lazy-supplier helpers.
/// - [CancelToken] — paradigm-agnostic cancellation token threaded through features.
/// - [Cancel] — bridge between LSP4J's `CancelChecker` and [CancelToken];
///   one of two places allowed to import `java.util.concurrent.CompletableFuture`
///   (the other being [com.almato.bromo.lsp]).
package com.almato.bromo.util;
