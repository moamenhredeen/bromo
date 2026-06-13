package me.moamenhredeen.bromo;

import me.moamenhredeen.bromo.lsp.BromoLanguageServer;
import me.moamenhredeen.bromo.wire.Stdio;

/// CLI entry for bromo.
///
/// Currently only `--stdio` is supported. Future modes (`--socket=PORT`,
/// `--pipe=path`) attach the same [BromoLanguageServer] to alternate transports.
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        var mode = args.length > 0 ? args[0] : "--stdio";
        switch (mode) {
            case "--stdio" -> Stdio.run(new BromoLanguageServer());
            case "--help", "-h" -> printUsage(System.out);
            default -> {
                printUsage(System.err);
                System.exit(2);
            }
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("""
                bromo — an ultra-fast Java language server.

                Usage:
                  bromo --stdio         Speak LSP over stdin/stdout (default).
                  bromo --help          Show this help.
                """);
    }
}
