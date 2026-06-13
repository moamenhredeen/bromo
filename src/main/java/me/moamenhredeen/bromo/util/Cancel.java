package me.moamenhredeen.bromo.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

/// Bridge between LSP4J's `CancelChecker` and bromo's [CancelToken].
///
/// One of two places in the codebase (alongside [me.moamenhredeen.bromo.lsp])
/// permitted to import `org.eclipse.lsp4j.*` and
/// `java.util.concurrent.CompletableFuture`.
///
/// After the R2 replacement track drops LSP4J, this class collapses into
/// direct cancel-token plumbing from `$/cancelRequest`.
public final class Cancel {
    private Cancel() {}

    /// Wraps an LSP4J [CancelChecker] as a [CancelToken].
    public static CancelToken fromChecker(CancelChecker checker) {
        return () -> {
            try {
                checker.checkCanceled();
                return false;
            } catch (CancellationException cancelled) {
                return true;
            }
        };
    }

    /// Executes [body] on [executor] (typically a virtual-thread executor) and
    /// returns a `CompletableFuture<T>`. The body sees a [CancelToken] derived
    /// from [checker]; the body is paradigm-agnostic and never imports LSP4J.
    public static <T> CompletableFuture<T> async(
            CancelChecker checker,
            Executor executor,
            Function<CancelToken, T> body) {
        var token = fromChecker(checker);
        return CompletableFuture.supplyAsync(() -> body.apply(token), executor);
    }
}
