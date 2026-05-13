package com.almato.bromo.compiler;

import com.almato.bromo.project.ClasspathEntry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/// Resolves library source code (`-sources.jar` from `~/.m2/repository`)
/// for binary bindings that point at classpath dependencies.
///
/// Mirrors the JDK source-attachment pattern in [com.almato.bromo.jdk.JdkProvider]
/// but draws from per-[ClasspathEntry] source jars instead of `src.zip`.
///
/// **Index build is lazy.** The first cross-library goto-def pays for
/// scanning every classpath jar to map binary top-level type FQNs onto
/// their owning [ClasspathEntry]. Subsequent lookups are `Map.get` and
/// an extraction step. Building the index eagerly would inflate
/// `attachToRoot` time (the metric driving the R1 trigger); building it
/// per-request would burn that time on every goto-def. Lazy-once is
/// the compromise Eclipse JDT and IntelliJ also make.
///
/// **Zip filesystems** for each source jar are cached for the provider's
/// lifetime — opening one per lookup adds tens of ms.
///
/// **Extracted files** materialise to [#cacheDir] as plain `.java` so the
/// LSP can hand the client a `file://` URI (same reason as the JDK path).
public final class LibrarySourceProvider implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(LibrarySourceProvider.class.getName());

    private final List<ClasspathEntry> classpath;
    private final Path cacheDir;

    private volatile Map<String, ClasspathEntry> binaryIndex;
    private final Object indexLock = new Object();

    private final Map<Path, FileSystem> openZipFs = new ConcurrentHashMap<>();

    public LibrarySourceProvider(List<ClasspathEntry> classpath, Path cacheDir) {
        this.classpath = List.copyOf(classpath);
        this.cacheDir = cacheDir;
    }

    /// Returns the extracted `.java` file for the top-level type identified
    /// by `(packageName, simpleName)`, or empty if no classpath jar carries
    /// a matching `.class` or its source attachment is missing.
    public Optional<Path> resolveSource(String packageName, String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return Optional.empty();
        String fqn = packageName == null || packageName.isEmpty()
                ? simpleName
                : packageName + "." + simpleName;

        ClasspathEntry entry = index().get(fqn);
        if (entry == null) return Optional.empty();
        if (entry.sources().isEmpty()) return Optional.empty();

        return extract(entry, packageName, simpleName);
    }

    @Override
    public void close() {
        for (var fs : openZipFs.values()) {
            try { fs.close(); } catch (IOException ignored) {}
        }
        openZipFs.clear();
    }

    // ---- index -------------------------------------------------------------

    private Map<String, ClasspathEntry> index() {
        var idx = binaryIndex;
        if (idx != null) return idx;
        synchronized (indexLock) {
            if (binaryIndex == null) {
                binaryIndex = buildIndex();
            }
            return binaryIndex;
        }
    }

    /// Walks every classpath jar and records FQN → owning entry for each
    /// **top-level** `.class` file. Nested types (`Outer$Nested.class`) are
    /// skipped: navigating to `Map.Entry` opens `Map.java`, then [SourceResolver]
    /// walks the AST to the nested declaration — so we only need the
    /// top-level binding here.
    ///
    /// Earlier classpath entries win on FQN collisions, matching javac's
    /// classpath-ordering rules.
    private Map<String, ClasspathEntry> buildIndex() {
        var result = new HashMap<String, ClasspathEntry>(16384);
        for (ClasspathEntry entry : classpath) {
            indexJar(entry, result);
        }
        return result;
    }

    private void indexJar(ClasspathEntry entry, Map<String, ClasspathEntry> out) {
        Path jar = entry.binary();
        if (!Files.isRegularFile(jar)) return;
        try {
            FileSystem fs = openZip(jar);
            try (Stream<Path> walk = Files.walk(fs.getPath("/"))) {
                walk.filter(LibrarySourceProvider::isTopLevelClassEntry)
                        .forEach(p -> {
                            String fqn = toFqn(p);
                            if (fqn != null) {
                                out.putIfAbsent(fqn, entry);
                            }
                        });
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "indexing failed for " + jar + ": " + e.getMessage());
        }
    }

    private static boolean isTopLevelClassEntry(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString();
        if (!name.endsWith(".class")) return false;
        if (name.indexOf('$') >= 0) return false;
        if (name.equals("module-info.class") || name.equals("package-info.class")) return false;
        return true;
    }

    private static String toFqn(Path classEntry) {
        // Inside a jar FS the path looks like "/com/example/Foo.class".
        String s = classEntry.toString().replace('\\', '/');
        if (s.startsWith("/")) s = s.substring(1);
        if (!s.endsWith(".class")) return null;
        s = s.substring(0, s.length() - ".class".length());
        // Skip multi-release rooted entries (META-INF/versions/<N>/...): for our
        // top-level FQN map they'd duplicate the base entry under a bogus name.
        if (s.startsWith("META-INF/")) return null;
        return s.replace('/', '.');
    }

    // ---- extraction --------------------------------------------------------

    private Optional<Path> extract(ClasspathEntry entry, String packageName, String simpleName) {
        Path sourcesJar = entry.sources().orElseThrow();
        String packagePath = (packageName == null || packageName.isEmpty())
                ? ""
                : packageName.replace('.', '/') + "/";
        String entryName = packagePath + simpleName + ".java";

        // Cache target lives under a per-jar dir so different library versions
        // don't collide. Using the source jar's file name preserves version info
        // and stays human-readable while still being collision-free per coord.
        String jarKey = stripExtension(sourcesJar.getFileName().toString());
        Path target = cacheDir.resolve(jarKey).resolve(entryName);
        if (Files.isRegularFile(target)) return Optional.of(target);

        try {
            FileSystem fs = openZip(sourcesJar);
            Path inside = fs.getPath(entryName);
            if (!Files.isRegularFile(inside)) return Optional.empty();
            Files.createDirectories(target.getParent());
            byte[] bytes = Files.readAllBytes(inside);
            Path tmp = Files.createTempFile(target.getParent(), ".tmp-", ".java");
            Files.write(tmp, bytes);
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(target);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    () -> "source extract failed for " + entryName + " in " + sourcesJar
                            + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ---- zip filesystem cache ---------------------------------------------

    private FileSystem openZip(Path jar) throws IOException {
        FileSystem cached = openZipFs.get(jar);
        if (cached != null && cached.isOpen()) return cached;
        // Path-overloaded newFileSystem always returns a fresh instance, so
        // computeIfAbsent could race-spawn duplicates. Synchronise on the
        // map for the put. The cost of double-open in the rare race is the
        // file-handle leak until close(), which is bounded.
        synchronized (openZipFs) {
            FileSystem re = openZipFs.get(jar);
            if (re != null && re.isOpen()) return re;
            FileSystem fresh = FileSystems.newFileSystem(jar, (ClassLoader) null);
            openZipFs.put(jar, fresh);
            return fresh;
        }
    }
}
