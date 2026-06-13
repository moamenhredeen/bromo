/// Workspace state — open documents, file storage, revision tracking.
///
/// Mutable state in this package is protected per-document; the [FileStore]
/// uses concurrent maps so multiple URIs can be accessed in parallel. No
/// static mutable state — everything hangs off the [Workspace] root.
package me.moamenhredeen.bromo.workspace;
