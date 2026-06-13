/// Tier-1 symbol index — parse-only signature extraction across the workspace.
///
/// Backs LSP workspace symbols, type-name + import completion, find-references
/// target lookup, and the fast path for goto-def. No binding resolution: a
/// pure structural walk of each compilation unit via ECJ's `ASTParser` with
/// `setResolveBindings(false)`.
///
/// Tier 2 (per-file lazy attribution) lives in `compiler/` and lands at M4.
package me.moamenhredeen.bromo.symbol;
