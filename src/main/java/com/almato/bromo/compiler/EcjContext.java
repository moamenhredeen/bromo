package com.almato.bromo.compiler;

import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

/// ECJ-backed compile + diagnostics engine.
///
/// M4 model: stateless batch compile of the whole source tree per request.
/// **M4.5 (this revision)** keeps two pieces of state alive across calls:
/// 1. The [INameEnvironment] (a `BromoFileSystem`) — caches open `ZipFile`
///    handles for every jar on the classpath; reusing it avoids re-opening
///    them on every compile.
/// 2. A **diagnostics cache** keyed by per-file content signatures. Open
///    documents are signed by their `text().hashCode()`; closed files by
///    `(size, mtime)`. When all signatures match the previous compile, the
///    cached diagnostics are returned without re-compiling.
///
/// Acceptable scope of v0: the cache is all-or-nothing — any change anywhere
/// in the workspace invalidates the cache. Granular per-file invalidation
/// (compile only the touched CU + dependents, reuse binaries for the rest)
/// is a v1 follow-up needing real type-graph tracking.
///
/// Thread safety: [#compileWorkspace] is `synchronized` so concurrent edits
/// from independent virtual threads don't corrupt the embedded `Compiler`
/// state — ECJ's `Compiler` is not itself reentrant. Hot path: cache hit
/// returns in microseconds while holding the lock; cache miss blocks.
public final class EcjContext implements AutoCloseable {

    private final FileStore fileStore;
    private final List<Path> sourceRoots;
    private final List<Path> classpath;

    private final Object stateLock = new Object();
    private INameEnvironment cachedEnv;
    private Map<Path, Long> lastSignatures = Map.of();
    private Map<URI, List<Diagnostic>> lastDiagnostics = Map.of();

    public EcjContext(FileStore fileStore, List<Path> sourceRoots, List<Path> classpath) {
        this.fileStore = fileStore;
        this.sourceRoots = List.copyOf(sourceRoots);
        this.classpath = List.copyOf(classpath);
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            evictEnvLocked();
            lastSignatures = Map.of();
            lastDiagnostics = Map.of();
        }
    }

    public List<Path> sourceRoots() { return sourceRoots; }
    public List<Path> classpath()   { return classpath; }
    public FileStore  files()       { return fileStore; }

    /// Compile every `.java` file under the configured source roots and
    /// return diagnostics keyed by source URI. Returns the **previous** result
    /// directly when every file's signature matches the last compile.
    public synchronized Map<URI, List<Diagnostic>> compileWorkspace() throws IOException {
        var files = collectJavaFiles(sourceRoots);
        var signatures = computeSignatures(files);

        synchronized (stateLock) {
            if (!lastDiagnostics.isEmpty() && signatures.equals(lastSignatures)) {
                return lastDiagnostics;
            }
        }

        Map<URI, List<Diagnostic>> fresh = compile(files);

        synchronized (stateLock) {
            this.lastSignatures = signatures;
            this.lastDiagnostics = fresh;
        }
        return fresh;
    }

    public synchronized Map<URI, List<Diagnostic>> compile(List<Path> files) throws IOException {
        var results = new HashMap<URI, List<Diagnostic>>();
        if (files.isEmpty()) return results;

        var unitMap = new HashMap<String, URI>();
        var units = new ICompilationUnit[files.size()];
        for (int i = 0; i < files.size(); i++) {
            Path path = files.get(i);
            URI uri = path.toUri();
            String unitName = normaliseName(path.toString());
            unitMap.put(unitName, uri);
            char[] content = contentOf(path, uri);
            units[i] = new org.eclipse.jdt.internal.compiler.batch.CompilationUnit(
                    content, path.toString(), "UTF-8");
            results.put(uri, new ArrayList<>());
        }

        INameEnvironment env = acquireEnvLocked();
        try {
            ICompilerRequestor requestor = result -> {
                String name = normaliseName(new String(result.getCompilationUnit().getFileName()));
                URI uri = unitMap.get(name);
                if (uri == null) return;
                var diags = results.computeIfAbsent(uri, _ -> new ArrayList<>());
                var problems = result.getAllProblems();
                if (problems == null) return;
                for (var problem : problems) {
                    diags.add(ProblemBridge.toDiagnostic(uri, problem));
                }
            };

            IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
            IProblemFactory problemFactory = new DefaultProblemFactory();
            CompilerOptions options = new CompilerOptions(buildOptionsMap());

            new Compiler(env, policy, options, requestor, problemFactory).compile(units);
        } catch (RuntimeException re) {
            // Conservative: a Compiler crash may have left the env in a bad state.
            evictEnv();
            throw re;
        }
        return results;
    }

    /// Parse [content] (the source of [uri]) into a DOM [CompilationUnit] with
    /// binding resolution enabled. Drives hover, goto-def, and completion.
    @SuppressWarnings("deprecation") // AST.JLS_Latest is intentionally the highest available
    public CompilationUnit parseWithBindings(URI uri, char[] content) {
        var parser = ASTParser.newParser(AST.JLS_Latest);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(astOptions());
        parser.setSource(content);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setUnitName("/" + uri.getPath());

        String[] cp = classpath.stream().map(Path::toString).toArray(String[]::new);
        String[] sp = sourceRoots.stream().map(Path::toString).toArray(String[]::new);
        String[] encodings = new String[sp.length];
        Arrays.fill(encodings, "UTF-8");
        parser.setEnvironment(cp, sp, encodings, true);

        return (CompilationUnit) parser.createAST(null);
    }

    private static Map<String, String> astOptions() {
        String latest = JavaCore.latestSupportedJavaVersion();
        return Map.of(
                JavaCore.COMPILER_SOURCE, latest,
                JavaCore.COMPILER_COMPLIANCE, latest,
                JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, latest);
    }

    // ---- caching helpers ---------------------------------------------------

    private Map<Path, Long> computeSignatures(List<Path> files) throws IOException {
        var result = new HashMap<Path, Long>(files.size());
        for (Path path : files) {
            result.put(path, signatureOf(path));
        }
        return result;
    }

    /// Cheap signature distinguishing one file revision from another:
    /// - **Open documents**: `text().hashCode()` snapshot. Fresh on every edit.
    /// - **Closed files**: `size XOR mtime` from filesystem metadata. No read.
    private long signatureOf(Path path) throws IOException {
        var open = fileStore.getOpen(path.toUri());
        if (open.isPresent()) {
            return open.get().text().hashCode();
        }
        long size = Files.size(path);
        long mtime = Files.getLastModifiedTime(path).toMillis();
        return size ^ (mtime << 32);
    }

    // ---- env lifecycle -----------------------------------------------------

    private INameEnvironment acquireEnvLocked() {
        var env = cachedEnv;
        if (env != null) return env;
        synchronized (stateLock) {
            if (cachedEnv == null) {
                cachedEnv = newNameEnvironment();
            }
            return cachedEnv;
        }
    }

    private void evictEnv() {
        synchronized (stateLock) {
            evictEnvLocked();
        }
    }

    private void evictEnvLocked() {
        if (cachedEnv != null) {
            try {
                cachedEnv.cleanup();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            cachedEnv = null;
        }
    }

    private INameEnvironment newNameEnvironment() {
        var entries = new ArrayList<FileSystem.Classpath>();
        String jdkHome = System.getProperty("java.home");
        FileSystem.Classpath jrt = FileSystem.getJrtClasspath(jdkHome, "UTF-8", null, null);
        if (jrt != null) entries.add(jrt);
        for (Path p : classpath) {
            FileSystem.Classpath cp = FileSystem.getClasspath(p.toString(), "UTF-8", null);
            if (cp != null) entries.add(cp);
        }
        return new BromoFileSystem(entries.toArray(new FileSystem.Classpath[0]));
    }

    /// Thin subclass to access `FileSystem`'s protected `(Classpath[], …)` constructor.
    /// The public `(String[], …)` ctor doesn't take pre-built `Classpath` entries —
    /// which we need to inject the JDK's `jrt-fs` view.
    private static final class BromoFileSystem extends FileSystem {
        BromoFileSystem(Classpath[] paths) {
            super(paths, new String[0], false);
        }
    }

    // ---- compile helpers ---------------------------------------------------

    private char[] contentOf(Path path, URI uri) throws IOException {
        var open = fileStore.getOpen(uri);
        if (open.isPresent()) {
            return open.get().text().toCharArray();
        }
        return Files.readString(path, StandardCharsets.UTF_8).toCharArray();
    }

    private static Map<String, String> buildOptionsMap() {
        String latest = JavaCore.latestSupportedJavaVersion();
        var m = new HashMap<String, String>();
        m.put(JavaCore.COMPILER_SOURCE, latest);
        m.put(JavaCore.COMPILER_COMPLIANCE, latest);
        m.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, latest);
        m.put(JavaCore.CORE_ENCODING, "UTF-8");
        return m;
    }

    private static List<Path> collectJavaFiles(List<Path> sourceRoots) throws IOException {
        var result = new ArrayList<Path>();
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(result::add);
            }
        }
        return result;
    }

    private static String normaliseName(String pathString) {
        return pathString.replace('\\', '/');
    }
}
