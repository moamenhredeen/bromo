# bromo — project conventions

bromo is an ultra-fast Java language server (Java 25, embedded ECJ). The full implementation plan lives at `.claude/plans/i-want-to-build-snug-canyon.md` — read it first; this file is the durable conventions and constraints layered on top.

## Priorities (in order)

1. **Edit latency.** Keystroke → diagnostic / completion within the budgets specified in the plan.
2. **Cold start.** <1s p50 to first usable response on a 500-file workspace; attacked via Project Leyden AOT (JEP 483 / 514 / 515) + AppCDS while preserving the C2 JIT.
3. **Memory.** Flat scaling with workspace size; sub-ms GC pauses (ZGC generational).
4. **Correctness.** Bounded divergence from javac, enforced by the ref-divergence corpus harness.
5. **Minimal deps in steady state.** v0 keeps LSP4J + Maven Resolver behind isolation boundaries; both are scheduled for replacement on bench-gated triggers (R1, R2 in the plan). ECJ is the only library that stays.

## Hard rules (never violate; enforced by `arch/` tests)

- **No GraalVM native-image.** We need HotSpot's JIT. Cold start is attacked via AOT class loading + method profiling + AppCDS, not by giving up the JIT.
- **No `CompletableFuture` outside `lsp/`** and `util/Cancel.java`. Features are virtual-thread-blocking and synchronous, with `StructuredTaskScope` for fan-out.
- **No `org.eclipse.lsp4j.*` imports outside `lsp/`.** All LSP4J types are mapped to our own records at the service boundary in `lsp/Adapters.java`.
- **No `org.eclipse.aether.*` imports outside `project/maven/resolver/`.** Behind the SPI.
- **No reflection on the hot path.** We hand-rolled JSON / dispatch / data structures specifically to avoid it. Don't reintroduce.
- **No `static` mutable state.** Dependencies pass through constructors; `Workspace` is the root container. `static final` immutable data is fine.
- **No `Charset.defaultCharset()`.** UTF-8 everywhere, explicit `StandardCharsets.UTF_8` at every I/O boundary.

## Java idioms (use them)

- **Records** for all data, params, results, query keys / values.
- **Sealed types** for closed hierarchies (`LspMessage`, `Query<T>`, `ProjectModel`, …). Pattern-match exhaustively; let the compiler enforce coverage.
- **Pattern matching for switch** — including type patterns and deconstruction. Replaces visitors in most AST work.
- **Virtual threads** — one per LSP request. `Thread.ofVirtual().factory()` / `Executors.newThreadPerTaskExecutor(...)`. Blocking I/O is fine.
- **`StructuredTaskScope`** for fan-out. `ShutdownOnFailure` is the default; `ShutdownOnSuccess` for races.
- **`ScopedValue`** for request context (id, cancel token, start time). Not `ThreadLocal`.
- **`MemorySegment` + `Arena`** for off-heap source buffers and large read-only data. Arenas tied to module lifecycles; close explicitly.
- **JEP 467 Markdown javadoc** (`///`) for all new doc comments. No HTML-in-javadoc.

Avoid: visitors with sprawling `switch` ladders, `CompletableFuture.thenApply` chains, builders for ≤4-field types (use records), reflective frameworks of any kind, annotation processors on the hot path.

### JEP 467 javadoc example

```java
/// Resolves the binding at the given offset in [uri], if any.
///
/// Cheap when the file's attribution is cached; otherwise drives a
/// `JavacTask`-equivalent pass through the long-lived `Compiler`.
///
/// @param uri the document URI
/// @param offset 0-based char offset in the current revision's content
/// @param cancel cancellation token; checked between major phases
/// @return the resolved binding, or `null` if no binding applies at [offset]
public IBinding bindingAt(URI uri, int offset, CancelToken cancel) { ... }
```

## JPMS layout

JPMS is used where it provides isolation that matters. Current target (may evolve as code lands):

- `me.moamenhredeen.bromo.foundation` — `util`, `workspace`, `query`, `symbol`. No compiler deps.
- `me.moamenhredeen.bromo.engine` — `compiler`, `features`. Depends on `foundation` + ECJ.
- `me.moamenhredeen.bromo.transport` — `wire`, `lsp`. Depends on `engine` + (v0) LSP4J.
- `me.moamenhredeen.bromo.project.maven.resolver` — Maven Resolver provider (v0). Depends on `foundation` + Maven Resolver.
- `me.moamenhredeen.bromo.app` — `Main`, bootstrap, glue. Depends on everything.

Notes:
- ECJ, LSP4J, and Maven Resolver may be **automatic modules**. Tolerated for now; pin module names where used in `requires`.
- Use `requires static` for v0 deps scheduled for swap (LSP4J, Resolver). The swap-in module supplies the same SPI surface.
- Every module has a `module-info.java` and every package has a `package-info.java` with a one-paragraph description in markdown javadoc.
- Strong encapsulation: `exports` only the SPI / public surface; everything else is module-private.

## Adapter discipline

The single most important architectural rule. Two enforced boundaries:

1. **`lsp/` ↔ `features/`** — LSP4J's `CompletableFuture<T>` and LSP4J types stop at `lsp/`. Features take our param records + a `CancelToken` and return our result records, synchronously.
2. **`project/maven/resolver/` ↔ `project/`** — All Maven Resolver / Aether types stop at the resolver provider. The rest of the project sees `ProjectModel` records only.

These boundaries make the post-v0 replacement track possible without touching feature code. Architecture tests fail the build on violation.

## Testing (TDD by default)

Write the test first. Test the public API of each module, not implementation details. The bromo testing posture is **a lot of tests, real dependencies, fast feedback**.

### Test pyramid

- **Unit tests** — fast, parallel-safe (`@Execution(CONCURRENT)`), no I/O when avoidable. Records' equality + sealed-type pattern coverage make these cheap.
- **Integration tests** — exercise **real ECJ**, real piece-tables, real `MemorySegment` mmap on tempdirs, real `pom.xml` files. **Don't mock the compiler. Don't mock the filesystem.**
- **End-to-end tests** — drive the LSP server in-process (LSP4J `LSPLauncher` in v0, our wire in v0.1+). One LSP request, one response, real ECJ behind it.
- **Property-based tests** — `jqwik` (test scope only) for: piece-table edits (apply N random edits, assert content matches a reference `String`), JSON codec round-tripping, query cache invalidation, input-tracking correctness, symbol-index trie operations.
- **Soak tests** — long-running. 1000+ edits randomly interleaved with feature requests; assert p99 latency + heap RSS bounds.
- **Ref-divergence corpus** — `mvn compile` (javac) vs `EcjContext` attribution; diff diagnostics by `(file, line, problemId)`; allowlist for known deltas; new deltas fail CI.
- **Architecture tests** — JUnit + a small class-scanner in `arch/`. Enforce the hard rules above.

### Coverage

- ≥90% line coverage in `foundation` and `engine` modules.
- ≥75% elsewhere.
- Coverage is a floor, not a target. Write tests that justify the latency budgets in the plan, not tests that touch a line for its own sake.

### Conventions

- Naming: `methodUnderTest_givenInput_expectsOutcome` or `@DisplayName` sentence. Pick one per module and be consistent.
- One assertion per concept (multiple physical assertions are fine).
- No `Thread.sleep` in tests. Use synchronous APIs or proper synchronization primitives.
- Tempdirs via `@TempDir` (JUnit Jupiter); cleaned automatically.

## Benchmarking

Bench harness lives in `bench/` (test scope; hand-rolled, no JMH dep).

- Every milestone produces a baseline JSON file checked into `bench/baselines/`.
- CI runs the bench suite and fails on >10% regression vs baseline.
- New baselines require an explicit commit with `bench: rebaseline <reason>`.
- Trigger metrics for the replacement track (R1 project-model load time; R2 wire dispatch overhead) are surfaced in the bench report on every PR.

Methodology:
- `System.nanoTime()` for measurement. `Instant.now()` only for wall-clock display.
- Warm up before timing (≥1000 iterations or 5s, whichever first).
- Discard the first quartile of samples.
- Report p50 / p95 / p99 — never just mean.

## Documentation

- **Markdown javadoc** (`///`) on every public class, method, and `package-info.java`. Aim for: one-sentence summary, `@param` / `@return`, side effects, thread-safety / concurrency expectations.
- **No comments that restate the code.** Comments explain *why*, not *what*.
- **ADRs (architecture decision records)** for any decision that reverses a hard rule or adds a runtime dep. Live in `docs/adr/NNN-title.md`. Reference the plan when relevant.

## Threading invariants

- **stdout** is written by exactly one thread (LSP4J handles in v0; our dispatcher will in v0.1).
- **ECJ calls** happen on virtual threads. They block freely. Cancellation propagates via the `CancelToken` ↔ `IProgressMonitor` bridge in `compiler/`.
- **`Workspace` state** is mutated only via methods that take a write lock; reads are lock-free against immutable snapshots.
- **`QueryEngine`** caches are concurrent maps; computations may run in parallel; results are pure functions of inputs + revisions.
- **`Arena`s** are tied to module / module-context lifecycles. Don't share segments across modules. Close arenas explicitly when modules unload.

## Allocation discipline (hot path only)

Hot path = code on the keystroke / completion / hover / diagnostic-publish path.

- Prefer `byte[]` + offset/length over `String`. Materialize `String` only when handing to an API that requires it.
- Reuse buffers via pooled `Arena`s or per-task scoped carriers. (`ThreadLocal` is a last resort — incompatible with virtual-thread-per-request.)
- No boxing — primitive arrays, primitive specializations (`IntStream`, `LongStream`), records of primitives.
- No varargs (allocates `Object[]`).
- No streams with intermediate operations in tight loops — write the loop.
- No regex on hot paths (compile cost + matcher allocation). Hand-written scanners.

Cold-path code (init, project model load, one-shot workspace scan) doesn't need this discipline. Use idiomatic Java.

## Encoding

- All source files UTF-8.
- All file I/O specifies `StandardCharsets.UTF_8` explicitly.
- Never `Charset.defaultCharset()`.
- LSP messages are UTF-8 per spec.

## Logging

- `java.lang.System.Logger` only. No SLF4J / Log4j2 / Logback.
- Hot path tracing is **not** logging — it's a per-request ring buffer of structured trace events, pre-allocated, dumped on error or via a debug request. Loggers are never called on the keystroke / completion / hover path.
- Construct log messages lazily — `logger.log(Level.DEBUG, () -> "..." + expensive())`, so the message is built only if the level is enabled (~5–15ns when disabled).
- Never log file contents at INFO+. Trace level only.

**Why not Log4j2:** its async-logger throughput advantage (millions of events/sec) is irrelevant when our cool path logs hundreds/sec at most and the hot path doesn't log at all. Log4j2 adds ~2MB of deps for zero practical perf win in our context. If async cool-path logging ever becomes necessary, hand-roll an async backend (~150 LOC: `ConcurrentLinkedQueue` + a background virtual thread draining to stderr) bound as a `System.Logger` SPI. Matches Log4j2's async behavior, zero deps, naturally uses virtual threads.

## Commit messages

Commits document **why**, not what. `git diff` already shows what changed; the message exists to capture intent, reasoning, and the technical decisions behind the change so the history is useful months later.

- **Lead with the reasoning.** Subject line states the change in intent terms; body explains the motivation, the alternatives considered, and the trade-offs accepted. A reader should understand *why this commit exists* without reading the diff.
- **Do not enumerate changes.** No bullet lists of touched files, renamed symbols, or moved lines. If the diff is too large to grasp from a focused message, the commit is too large — split it.
- **Capture decisions.** If the change locks in a technical choice (dep selection, algorithm, boundary, threading model), say what was chosen, what was rejected, and on what grounds. Future-you needs to know what evidence would justify reversing it.
- **No AI-attribution trailers.** No `Co-Authored-By: Claude …`, `Generated-By:`, or similar lines. Commit history attributes to the human author only, regardless of tooling.
- **No filler.** Skip "this commit", "various changes", "misc cleanup". If a sentence doesn't add reasoning, delete it.
- **Subject line** in imperative mood, ≤72 chars, no trailing period. Body wraps at ~72 chars, separated from subject by a blank line.

## Build & run

Filled in as commands land. Expected shape:

```
# Build
mvn package

# Run against bromo-itself
./bromo --stdio --workspace .

# Bench
mvn test -Pbench

# Ref-divergence corpus
mvn test -Pverify

# All tests
mvn verify
```

## Out of scope

- GraalVM native-image — see Hard rules.
- Gradle / Bazel project model — post-v0; `ProjectModelProvider` SPI is in place.
- Refactorings beyond v0 MVP (rename, extract method, find-references, organize-imports) — v1.
- Lombok / full annotation processor support — v1, separate milestone.
- Source attachment from `-sources.jar` — v0.1.
- IDE-specific protocol extensions (Neovim/VSCode/etc.) — stay LSP-spec-pure.
