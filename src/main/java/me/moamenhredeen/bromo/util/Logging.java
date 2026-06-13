package me.moamenhredeen.bromo.util;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.function.Supplier;

/// Thin wrapper around `java.lang.System.Logger` providing lazy-supplier helpers.
///
/// **Do not call from the hot path** (keystroke / completion / hover / diagnostic
/// publish). Hot-path tracing uses a per-request ring buffer; see the plan and
/// the `Logging` section of CLAUDE.md.
public final class Logging {
    private Logging() {}

    public static Logger get(Class<?> owner) {
        return System.getLogger(owner.getName());
    }

    public static void trace(Logger log, Supplier<String> msg) {
        log.log(Level.TRACE, msg);
    }

    public static void debug(Logger log, Supplier<String> msg) {
        log.log(Level.DEBUG, msg);
    }

    public static void info(Logger log, Supplier<String> msg) {
        log.log(Level.INFO, msg);
    }

    public static void warn(Logger log, Supplier<String> msg) {
        log.log(Level.WARNING, msg);
    }

    public static void error(Logger log, Supplier<String> msg, Throwable t) {
        log.log(Level.ERROR, msg.get(), t);
    }
}
