package me.moamenhredeen.bromo.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/// Guards the cold-start tuning baked into the `bromo` / `bromo.bat`
/// launcher scripts.
///
/// The flags below are load-bearing for the plan's cold-start target
/// (`<1s p50 first usable response`). Dropping them silently in a refactor
/// would regress cold start without a single Java test breaking — so
/// pin them here, in a build-failing form.
final class LauncherFlagsTest {

    @Test
    @DisplayName("POSIX launcher carries ZGC + AOT flags")
    void posixLauncherHasColdStartFlags() throws IOException {
        assertLauncherFlags(Path.of("bromo"));
    }

    @Test
    @DisplayName("Windows launcher carries ZGC + AOT flags")
    void windowsLauncherHasColdStartFlags() throws IOException {
        assertLauncherFlags(Path.of("bromo.bat"));
    }

    private static void assertLauncherFlags(Path script) throws IOException {
        String text = Files.readString(script, StandardCharsets.UTF_8);
        assertTrue(text.contains("--enable-preview"),
                script + " must keep --enable-preview (Java 25 preview features)");
        assertTrue(text.contains("-XX:+UseZGC"),
                script + " must keep -XX:+UseZGC (generational ZGC for sub-ms pauses)");
        assertTrue(text.contains("-XX:AOTMode=auto"),
                script + " must keep -XX:AOTMode=auto (JEP 514 one-step AOT)");
        assertTrue(text.contains("-XX:AOTCache="),
                script + " must keep -XX:AOTCache=... (consumed by AOT auto mode)");
    }
}
