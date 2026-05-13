package com.almato.bromo.symbol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/// Builds a [SymbolIndex] by parallel-scanning every `.java` file under the
/// given source roots.
///
/// Concurrency: one virtual thread per file via
/// `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads are
/// stable since Java 21 and avoid the preview surface area of
/// `StructuredTaskScope` — we adopt the latter at M4 when query
/// cancellation becomes load-bearing.
///
/// Failure isolation: an extraction error on one file is logged and skipped;
/// the scan continues. A bad file shouldn't kill the workspace.
public final class WorkspaceScanner {

    private final SignatureExtractor extractor = new SignatureExtractor();

    public ScanResult scan(List<Path> sourceRoots) {
        return scanInto(new SymbolIndex(), sourceRoots);
    }

    /// Variant that populates an existing [SymbolIndex] (so the workspace
    /// keeps a single, growing index rather than swapping references).
    public ScanResult scanInto(SymbolIndex index, List<Path> sourceRoots) {
        long startNs = System.nanoTime();
        var files = collectJavaFiles(sourceRoots);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<List<Descriptor>>>(files.size());
            for (var file : files) {
                futures.add(executor.submit(() -> safeExtract(file)));
            }
            for (var fut : futures) {
                try {
                    index.addAll(fut.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    // Skipped — safeExtract caught its own IOExceptions; an
                    // ExecutionException here is something unexpected.
                    System.getLogger(WorkspaceScanner.class.getName())
                            .log(System.Logger.Level.WARNING,
                                    "scan task failed", e.getCause());
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        return new ScanResult(index, files.size(), elapsedMs);
    }

    private List<Descriptor> safeExtract(Path file) {
        try {
            return extractor.extract(file);
        } catch (IOException ioe) {
            return List.of();
        } catch (RuntimeException re) {
            // ECJ occasionally chokes on malformed syntax in recovery mode; survive it.
            return List.of();
        }
    }

    private static List<Path> collectJavaFiles(List<Path> sourceRoots) {
        var result = new ArrayList<Path>();
        for (var root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .forEach(result::add);
            } catch (IOException e) {
                System.getLogger(WorkspaceScanner.class.getName())
                        .log(System.Logger.Level.WARNING, "unable to walk " + root, e);
            }
        }
        return result;
    }

    public record ScanResult(SymbolIndex index, int fileCount, long elapsedMs) {}
}
