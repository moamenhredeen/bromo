/// Derived-data cache layer ("query engine").
///
/// Sits above [me.moamenhredeen.bromo.compiler.EcjContext] and below the feature
/// handlers. Callers ask for a query (today: parsed AST with bindings); the
/// engine returns a cached value when its inputs are unchanged, or
/// recomputes on miss.
///
/// Cache invalidation is driven by [me.moamenhredeen.bromo.workspace.FileStore]
/// change events — the engine subscribes on construction.
package me.moamenhredeen.bromo.query;
