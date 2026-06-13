package me.moamenhredeen.bromo.util;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/// Cancellation token threaded through feature execution.
///
/// Decoupled from LSP4J's `CancelChecker` so feature code remains
/// paradigm-agnostic. The LSP4J-to-token conversion lives in [Cancel].
///
/// Feature handlers poll [#isCancelled()] at safe points; the ECJ progress-monitor
/// bridge polls it between compilation phases.
@FunctionalInterface
public interface CancelToken {

    /// Snapshot of cancellation state. Cheap; safe to call in tight loops.
    boolean isCancelled();

    /// Throws [CancellationException] if cancelled. Convenience for cancel-aware loops.
    default void check() {
        if (isCancelled()) throw new CancellationException("operation cancelled");
    }

    /// A token that is never cancelled. Useful in tests and for "fire and forget" work.
    static CancelToken never() {
        return () -> false;
    }

    /// A token derived from any boolean supplier.
    static CancelToken from(BooleanSupplier supplier) {
        return supplier::getAsBoolean;
    }
}
