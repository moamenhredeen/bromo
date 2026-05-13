package com.almato.bromo.bench;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/// Measures the round-trip latency of a no-op LSP request over the wire.
///
/// In v0 this drives LSP4J over piped streams; in v0.1+ it drives the
/// hand-rolled NIO + JSON codec wire. The bench is identical at the boundary,
/// which means the same baseline JSON file in `bench/baselines/` can compare
/// the two implementations directly when R2 fires.
final class WireRoundtripBench {

    @Test
    @Disabled("scaffolding — full wiring lands at M0 acceptance")
    void echoRoundTrip() {
        // TODO M0 acceptance:
        //  1. spin up BromoLanguageServer + a test client over PipedInputStream pairs
        //  2. BenchRunner.measure(warmup=1000, measure=10000, () -> client.initialize(...))
        //  3. assert result.p95ns() < 5_000_000  // 5ms ceiling on v0
        //  4. write result to bench/baselines/wire_roundtrip_v0.json
    }
}
