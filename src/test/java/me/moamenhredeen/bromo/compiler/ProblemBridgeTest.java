package me.moamenhredeen.bromo.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ProblemBridgeTest {

    private static final URI URI_X = URI.create("file:///x/X.java");

    @Test
    @DisplayName("error severity maps to ERROR; range is exclusive at end")
    void errorMapping() {
        var problem = new StubProblem(/*error*/ true, false, false, 10, 14, "bad");
        var d = ProblemBridge.toDiagnostic(URI_X, problem);
        assertEquals(DiagnosticSeverity.ERROR, d.severity());
        assertEquals(10, d.startOffset());
        assertEquals(15, d.endOffset());      // 14 inclusive → 15 exclusive
        assertEquals("bad", d.message());
        assertEquals(URI_X, d.uri());
    }

    @Test
    @DisplayName("warning maps to WARNING")
    void warningMapping() {
        var problem = new StubProblem(false, true, false, 1, 2, "warn");
        assertEquals(DiagnosticSeverity.WARNING, ProblemBridge.toDiagnostic(URI_X, problem).severity());
    }

    @Test
    @DisplayName("info maps to INFO")
    void infoMapping() {
        var problem = new StubProblem(false, false, true, 1, 2, "info");
        assertEquals(DiagnosticSeverity.INFO, ProblemBridge.toDiagnostic(URI_X, problem).severity());
    }

    @Test
    @DisplayName("negative source positions are clamped to 0")
    void negativePositionsClampedToZero() {
        var problem = new StubProblem(true, false, false, -1, -1, "x");
        var d = ProblemBridge.toDiagnostic(URI_X, problem);
        assertEquals(0, d.startOffset());
        assertEquals(0, d.endOffset());
    }

    // ---- stub --------------------------------------------------------------

    /// Hand-rolled `CategorizedProblem` so the bridge can be tested without
    /// driving a full ECJ compile.
    private static final class StubProblem extends CategorizedProblem {
        private final boolean error, warning, info;
        private final int start, end;
        private final String msg;

        StubProblem(boolean error, boolean warning, boolean info, int start, int end, String msg) {
            this.error = error; this.warning = warning; this.info = info;
            this.start = start; this.end = end; this.msg = msg;
        }

        @Override public int    getID()               { return 42; }
        @Override public String getMessage()          { return msg; }
        @Override public String[] getArguments()      { return new String[0]; }
        @Override public int    getSourceStart()      { return start; }
        @Override public int    getSourceEnd()        { return end; }
        @Override public int    getSourceLineNumber() { return 1; }
        @Override public char[] getOriginatingFileName() { return "X.java".toCharArray(); }
        @Override public void   setSourceStart(int s) {}
        @Override public void   setSourceEnd(int e)   {}
        @Override public void   setSourceLineNumber(int n) {}
        @Override public boolean isError()            { return error; }
        @Override public boolean isWarning()          { return warning; }
        @Override public boolean isInfo()             { return info; }
        @Override public int    getCategoryID()       { return CategorizedProblem.CAT_INTERNAL; }
        @Override public String getMarkerType()       { return "stub"; }
    }
}
