package me.moamenhredeen.bromo.compiler;

import java.net.URI;
import org.eclipse.jdt.core.compiler.CategorizedProblem;

/// Translates ECJ's `CategorizedProblem` (an `IProblem`) into bromo's
/// own [Diagnostic] record.
///
/// ECJ source ranges are inclusive `[sourceStart, sourceEnd]`; we convert
/// to exclusive `[start, end)` matching LSP and Java conventions.
public final class ProblemBridge {
    private ProblemBridge() {}

    public static Diagnostic toDiagnostic(URI uri, CategorizedProblem problem) {
        return new Diagnostic(
                uri,
                severityFor(problem),
                Math.max(0, problem.getSourceStart()),
                Math.max(0, problem.getSourceEnd() + 1),
                problem.getMessage(),
                Integer.toString(problem.getID()));
    }

    private static DiagnosticSeverity severityFor(CategorizedProblem problem) {
        if (problem.isError())   return DiagnosticSeverity.ERROR;
        if (problem.isWarning()) return DiagnosticSeverity.WARNING;
        if (problem.isInfo())    return DiagnosticSeverity.INFO;
        return DiagnosticSeverity.HINT;
    }
}
