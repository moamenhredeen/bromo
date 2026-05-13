package com.almato.bromo.jdk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/// Locates and extracts source files from the running JDK's `lib/src.zip`.
///
/// `src.zip` is the canonical source attachment shipped with every JDK
/// distribution; both Eclipse JDT and IntelliJ read it the same way for
/// goto-definition into `java.base` and friends. JREs (without the JDK
/// sources package) do not ship `src.zip` — in that case [#available()]
/// returns `false` and lookups return empty.
///
/// Modular layout (Java 9+): the zip's top-level entries are module
/// directories, e.g. `java.base/java/lang/String.java`. Older flat-layout
/// archives (Java 8) are not supported — bromo targets Java 25.
///
/// Extracted sources are materialised into [#cacheDir] as plain `.java`
/// files so the LSP can hand the client a `file://` URI. This avoids
/// `jar://` schemes that not every client understands (jdtls falls back
/// to the same strategy for non-vscode clients).
public final class JdkProvider implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(JdkProvider.class.getName());

    private final Optional<Path> srcZip;
    private final Path cacheDir;
    private volatile FileSystem zipFs;
    private final Object fsLock = new Object();

    public JdkProvider(Path cacheDir) {
        this(cacheDir, locateSrcZip());
    }

    JdkProvider(Path cacheDir, Optional<Path> srcZip) {
        this.cacheDir = cacheDir;
        this.srcZip = srcZip;
    }

    public boolean available() {
        return srcZip.isPresent();
    }

    /// Materialises the source file for the JDK class identified by
    /// `(module, packageName, simpleName)` into the cache directory.
    ///
    /// Returns `Optional.empty()` if `src.zip` isn't available, the entry
    /// doesn't exist, or any I/O failure occurs (logged at DEBUG).
    public Optional<Path> resolveSource(String module, String packageName, String simpleName) {
        if (srcZip.isEmpty() || module == null || module.isEmpty() || simpleName == null) {
            return Optional.empty();
        }
        String relative = buildRelative(module, packageName, simpleName);
        Path cached = cacheDir.resolve(relative);
        if (Files.isRegularFile(cached)) {
            return Optional.of(cached);
        }
        try {
            FileSystem fs = openZipFs();
            Path entry = fs.getPath(relative);
            if (!Files.isRegularFile(entry)) {
                return Optional.empty();
            }
            Files.createDirectories(cached.getParent());
            // Read-and-write rather than Files.copy, because the zip FS path
            // can't be the source of an atomic move and we want a UTF-8 string
            // round-trip to normalise line endings consistently.
            byte[] bytes = Files.readAllBytes(entry);
            Path tmp = Files.createTempFile(cached.getParent(), ".tmp-", ".java");
            Files.write(tmp, bytes);
            Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(cached);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, () -> "JDK source lookup failed for "
                    + module + "/" + packageName + "." + simpleName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        synchronized (fsLock) {
            if (zipFs != null) {
                try {
                    zipFs.close();
                } catch (IOException ignored) {
                }
                zipFs = null;
            }
        }
    }

    private FileSystem openZipFs() throws IOException {
        FileSystem fs = zipFs;
        if (fs != null && fs.isOpen()) return fs;
        synchronized (fsLock) {
            if (zipFs == null || !zipFs.isOpen()) {
                // `newFileSystem(Path, ClassLoader)` returns a fresh instance
                // per call, sidestepping the `URI`-overloaded variant's
                // JVM-wide uniqueness rule (which blows up with
                // FileSystemAlreadyExistsException across providers/tests).
                zipFs = FileSystems.newFileSystem(srcZip.orElseThrow(), (ClassLoader) null);
            }
            return zipFs;
        }
    }

    private static String buildRelative(String module, String packageName, String simpleName) {
        StringBuilder sb = new StringBuilder(module.length() + simpleName.length() + 32);
        sb.append(module);
        if (packageName != null && !packageName.isEmpty()) {
            sb.append('/').append(packageName.replace('.', '/'));
        }
        sb.append('/').append(simpleName).append(".java");
        return sb.toString();
    }

    private static Optional<Path> locateSrcZip() {
        String home = System.getProperty("java.home");
        if (home == null) return Optional.empty();
        Path candidate = Path.of(home, "lib", "src.zip");
        if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        // Some distributions place it one level up (e.g. when java.home points
        // at the JRE inside an older JDK layout); cheap fallback.
        Path parentCandidate = Path.of(home).resolveSibling("lib/src.zip");
        if (Files.isRegularFile(parentCandidate)) return Optional.of(parentCandidate);
        return Optional.empty();
    }

    /// Test-only factory that bypasses [#locateSrcZip] so the harness can point
    /// at a deterministic archive.
    public static JdkProvider withSrcZip(Path cacheDir, Path srcZip) {
        if (!Files.isRegularFile(srcZip)) {
            throw new UncheckedIOException(
                    new IOException("src.zip not found at " + srcZip));
        }
        return new JdkProvider(cacheDir, Optional.of(srcZip));
    }
}
