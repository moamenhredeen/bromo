package me.moamenhredeen.bromo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileStoreTest {

    @Test
    @DisplayName("openDocument tracks state and advances revision")
    void openDocumentTracksRevision(@TempDir Path tmp) {
        var fs = new FileStore();
        var uri = tmp.resolve("A.java").toUri();
        var doc = fs.openDocument(uri, "java", "class A {}");
        assertEquals("class A {}", doc.text());
        assertTrue(doc.revision().value() > 0);
        assertNotNull(fs.getOpen(uri).orElseThrow());
    }

    @Test
    @DisplayName("contentOf reads from disk when unopened")
    void contentOfReadsFromDiskWhenUnopened(@TempDir Path tmp) throws IOException {
        var fs = new FileStore();
        var path = tmp.resolve("B.java");
        Files.writeString(path, "package foo;\n", StandardCharsets.UTF_8);
        var content = fs.contentOf(path.toUri()).toString();
        assertEquals("package foo;\n", content);
    }

    @Test
    @DisplayName("contentOf prefers an open buffer over the disk file")
    void contentOfPrefersOpenBufferOverDisk(@TempDir Path tmp) throws IOException {
        var fs = new FileStore();
        var path = tmp.resolve("C.java");
        Files.writeString(path, "disk", StandardCharsets.UTF_8);
        fs.openDocument(path.toUri(), "java", "open");
        assertEquals("open", fs.contentOf(path.toUri()).toString());
    }

    @Test
    @DisplayName("closeDocument restores the disk view")
    void closeReturnsToDiskView(@TempDir Path tmp) throws IOException {
        var fs = new FileStore();
        var path = tmp.resolve("D.java");
        Files.writeString(path, "disk", StandardCharsets.UTF_8);
        fs.openDocument(path.toUri(), "java", "open");
        fs.closeDocument(path.toUri());
        assertEquals("disk", fs.contentOf(path.toUri()).toString());
    }

    @Test
    @DisplayName("changeListener fires for open / edit / close events")
    void changeListenerFires(@TempDir Path tmp) throws InterruptedException {
        var fs = new FileStore();
        var uri = tmp.resolve("E.java").toUri();
        var events = new ArrayBlockingQueue<java.net.URI>(8);
        fs.addChangeListener(events::add);

        fs.openDocument(uri, "java", "");
        assertEquals(uri, events.poll(2, TimeUnit.SECONDS));

        fs.notifyEdit(uri);
        assertEquals(uri, events.poll(2, TimeUnit.SECONDS));

        fs.closeDocument(uri);
        assertEquals(uri, events.poll(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("nextRevision is strictly monotonic")
    void nextRevisionMonotonic() {
        var fs = new FileStore();
        var a = fs.nextRevision();
        var b = fs.nextRevision();
        var c = fs.nextRevision();
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(c) < 0);
    }
}
