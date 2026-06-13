package me.moamenhredeen.bromo.symbol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/// Walks a Java compilation unit (parse-only — no binding resolution) and
/// emits [Descriptor]s for every type, method, constructor, and field.
///
/// This is **Tier 1** of the two-tier compiler model: cheap, broad, lexical.
/// Tier 2 (`compiler/`, M4) handles binding-aware queries; the symbol index
/// built here is its fast prefilter.
///
/// Stateless: a single instance is safe to share across threads.
public final class SignatureExtractor {

    @SuppressWarnings("deprecation") // newer JLS levels added each release; we want the highest
    private static final int JLS = AST.JLS_Latest;

    /// Compiler options that pin the parser to the newest grammar ECJ supports.
    /// Without this, ASTParser defaults to a much older source level and
    /// misparses modern constructs (e.g. records become methods named after
    /// the record's type).
    private static final Map<String, String> COMPILER_OPTIONS = Map.of(
            JavaCore.COMPILER_SOURCE,                   JavaCore.latestSupportedJavaVersion(),
            JavaCore.COMPILER_COMPLIANCE,               JavaCore.latestSupportedJavaVersion(),
            JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,  JavaCore.latestSupportedJavaVersion());

    public List<Descriptor> extract(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        char[] source = new String(bytes, StandardCharsets.UTF_8).toCharArray();
        return extract(file, source);
    }

    public List<Descriptor> extract(Path file, char[] source) {
        var parser = ASTParser.newParser(JLS);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(COMPILER_OPTIONS);
        parser.setSource(source);
        parser.setResolveBindings(false);
        parser.setStatementsRecovery(true);
        var cu = (CompilationUnit) parser.createAST(null);

        var results = new ArrayList<Descriptor>();
        cu.accept(new SignatureVisitor(file, cu, results));
        return results;
    }

    // ---- visitor -----------------------------------------------------------

    private static final class SignatureVisitor extends ASTVisitor {
        private final Path file;
        private final List<Descriptor> out;
        private final String packageName;
        private final Deque<String> typeStack = new ArrayDeque<>();

        SignatureVisitor(Path file, CompilationUnit cu, List<Descriptor> out) {
            this.file = file;
            this.out = out;
            this.packageName = cu.getPackage() != null
                    ? cu.getPackage().getName().getFullyQualifiedName()
                    : "";
        }

        @Override public boolean visit(TypeDeclaration n) {
            pushType(n, n.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS);
            return true;
        }
        @Override public void endVisit(TypeDeclaration n)            { typeStack.pop(); }

        @Override public boolean visit(EnumDeclaration n)            { pushType(n, SymbolKind.ENUM);       return true; }
        @Override public void endVisit(EnumDeclaration n)            { typeStack.pop(); }

        @Override public boolean visit(RecordDeclaration n)          { pushType(n, SymbolKind.RECORD);     return true; }
        @Override public void endVisit(RecordDeclaration n)          { typeStack.pop(); }

        @Override public boolean visit(AnnotationTypeDeclaration n)  { pushType(n, SymbolKind.ANNOTATION); return true; }
        @Override public void endVisit(AnnotationTypeDeclaration n)  { typeStack.pop(); }

        @Override
        public boolean visit(MethodDeclaration n) {
            String enclosing = typeStack.peek();
            String name = n.getName().getIdentifier();
            if (n.isConstructor()) {
                out.add(new Descriptor(
                        SymbolKind.CONSTRUCTOR,
                        (enclosing == null ? name : enclosing) + ".<init>",
                        name,
                        constructorSig(n),
                        file,
                        n.getName().getStartPosition(),
                        n.getName().getLength()));
            } else {
                String fqn = (enclosing == null ? name : enclosing + "." + name);
                out.add(new Descriptor(
                        SymbolKind.METHOD,
                        fqn,
                        name,
                        methodSig(n),
                        file,
                        n.getName().getStartPosition(),
                        n.getName().getLength()));
            }
            return false; // never descend into method bodies — Tier 1 is signatures only
        }

        @Override
        public boolean visit(FieldDeclaration n) {
            String enclosing = typeStack.peek();
            String type = n.getType().toString();
            for (Object fragmentObj : n.fragments()) {
                var frag = (VariableDeclarationFragment) fragmentObj;
                String name = frag.getName().getIdentifier();
                String fqn = (enclosing == null ? name : enclosing + "." + name);
                out.add(new Descriptor(
                        SymbolKind.FIELD,
                        fqn,
                        name,
                        type,
                        file,
                        frag.getName().getStartPosition(),
                        frag.getName().getLength()));
            }
            return false;
        }

        private void pushType(AbstractTypeDeclaration n, SymbolKind kind) {
            String simple = n.getName().getIdentifier();
            String parent = typeStack.peek();
            String fqn = parent != null
                    ? parent + "." + simple
                    : (packageName.isEmpty() ? simple : packageName + "." + simple);
            out.add(new Descriptor(
                    kind, fqn, simple, null, file,
                    n.getName().getStartPosition(),
                    n.getName().getLength()));
            typeStack.push(fqn);
        }

        private static String methodSig(MethodDeclaration m) {
            var sb = new StringBuilder(m.getName().getIdentifier());
            appendParams(sb, m);
            if (m.getReturnType2() != null) {
                sb.append(" : ").append(m.getReturnType2().toString());
            }
            return sb.toString();
        }

        private static String constructorSig(MethodDeclaration m) {
            var sb = new StringBuilder(m.getName().getIdentifier());
            appendParams(sb, m);
            return sb.toString();
        }

        private static void appendParams(StringBuilder sb, MethodDeclaration m) {
            sb.append('(');
            boolean first = true;
            for (Object p : m.parameters()) {
                var svd = (SingleVariableDeclaration) p;
                if (!first) sb.append(", ");
                sb.append(svd.getType().toString());
                first = false;
            }
            sb.append(')');
        }
    }
}
