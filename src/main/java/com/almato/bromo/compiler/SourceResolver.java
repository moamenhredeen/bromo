package com.almato.bromo.compiler;

import com.almato.bromo.features.DefinitionResult;
import com.almato.bromo.jdk.JdkProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IModuleBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/// Maps a binary [IBinding] (a JDK or library type, method, or field that
/// the project itself doesn't define) to a [DefinitionResult] pointing at
/// the declaration inside an extracted source attachment.
///
/// Mirrors the source-attachment lookup pattern used by Eclipse JDT and
/// IntelliJ: locate the source archive associated with the binary, extract
/// the relevant compilation unit, parse it, and walk the AST to find the
/// declaring node by name and (for methods) erased signature.
///
/// **v0 scope**: JDK sources only, via [JdkProvider]. Maven `-sources.jar`
/// attachments arrive in the follow-up commit; the same orchestration
/// applies — a different provider feeds [#resolve].
public final class SourceResolver {

    private final JdkProvider jdk;

    public SourceResolver(JdkProvider jdk) {
        this.jdk = jdk;
    }

    public Optional<DefinitionResult> resolve(IBinding binding) {
        if (binding == null) return Optional.empty();

        ITypeBinding owning = owningType(binding);
        if (owning == null) return Optional.empty();
        if (owning.isFromSource()) return Optional.empty();

        ITypeBinding topLevel = topLevel(owning);
        IModuleBinding module = topLevel.getModule();
        String moduleName = module == null ? null : module.getName();
        if (!isJdkModule(moduleName)) {
            // Not JDK — handled by the Maven sources resolver in the next
            // commit. Returning empty here lets DefinitionFeature surface
            // "no result" rather than landing the user at the wrong place.
            return Optional.empty();
        }

        String pkg = topLevel.getPackage() == null ? "" : topLevel.getPackage().getName();
        String simple = topLevel.getName();
        if (simple == null || simple.isEmpty()) return Optional.empty();

        Optional<Path> sourcePath = jdk.resolveSource(moduleName, pkg, simple);
        if (sourcePath.isEmpty()) return Optional.empty();

        URI sourceUri = sourcePath.get().toUri();
        CompilationUnit cu = parse(sourcePath.get());
        if (cu == null) {
            return Optional.of(new DefinitionResult(sourceUri, 0, 0));
        }

        AbstractTypeDeclaration enclosing = navigateToNestedType(cu, topLevel, owning);
        if (enclosing == null) {
            return Optional.of(new DefinitionResult(sourceUri, 0, 0));
        }

        ASTNode target = pickTarget(enclosing, binding);
        ASTNode anchor = target != null ? target : enclosing;
        Span name = nameSpan(anchor);
        return Optional.of(new DefinitionResult(
                sourceUri, name.start(), name.start() + name.length()));
    }

    // ---- binding navigation ------------------------------------------------

    private static ITypeBinding owningType(IBinding binding) {
        return switch (binding) {
            case ITypeBinding tb -> tb;
            case IMethodBinding mb -> mb.getDeclaringClass();
            case IVariableBinding vb -> vb.getDeclaringClass();
            default -> null;
        };
    }

    private static ITypeBinding topLevel(ITypeBinding tb) {
        ITypeBinding current = tb;
        while (current.getDeclaringClass() != null) {
            current = current.getDeclaringClass();
        }
        // generic types have a separate "raw" form; the file lives at the
        // erasure's simple name.
        ITypeBinding erasure = current.getErasure();
        return erasure != null ? erasure : current;
    }

    private static boolean isJdkModule(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.equals("java.base")
                || name.startsWith("java.")
                || name.startsWith("jdk.")
                || name.startsWith("javafx.");
    }

    // ---- AST navigation ----------------------------------------------------

    private AbstractTypeDeclaration navigateToNestedType(
            CompilationUnit cu, ITypeBinding topLevel, ITypeBinding owning) {

        List<String> chain = new ArrayList<>();
        for (ITypeBinding t = owning; t != null && t != topLevel; t = t.getDeclaringClass()) {
            chain.add(0, t.getName());
        }

        AbstractTypeDeclaration current = findTopLevel(cu, topLevel.getName());
        if (current == null) return null;
        for (String segment : chain) {
            current = findNested(current, segment);
            if (current == null) return null;
        }
        return current;
    }

    private static AbstractTypeDeclaration findTopLevel(CompilationUnit cu, String simpleName) {
        for (Object t : cu.types()) {
            if (t instanceof AbstractTypeDeclaration atd && atd.getName().getIdentifier().equals(simpleName)) {
                return atd;
            }
        }
        return null;
    }

    private static AbstractTypeDeclaration findNested(AbstractTypeDeclaration parent, String simpleName) {
        for (Object body : parent.bodyDeclarations()) {
            if (body instanceof AbstractTypeDeclaration atd
                    && atd.getName().getIdentifier().equals(simpleName)) {
                return atd;
            }
        }
        return null;
    }

    private static ASTNode pickTarget(AbstractTypeDeclaration enclosing, IBinding binding) {
        return switch (binding) {
            case ITypeBinding _ -> null; // anchor on the type declaration itself
            case IMethodBinding mb -> findMethod(enclosing, mb);
            case IVariableBinding vb -> vb.isField() || vb.isEnumConstant()
                    ? findField(enclosing, vb)
                    : null;
            default -> null;
        };
    }

    /// Match by simple name + parameter arity. If multiple overloads share both,
    /// prefer one whose erased parameter simple-names match; otherwise fall back
    /// to the first arity-matching method. Good enough for goto-def in v0;
    /// IntelliJ/jdtls use a richer signature match that we'd revisit if it
    /// proves wrong in practice.
    private static MethodDeclaration findMethod(AbstractTypeDeclaration enclosing, IMethodBinding mb) {
        String wantName = mb.isConstructor() ? enclosing.getName().getIdentifier() : mb.getName();
        int wantArity = mb.getParameterTypes().length;
        List<String> wantParams = erasedSimpleParamNames(mb);

        MethodDeclaration arityMatch = null;
        for (Object body : enclosing.bodyDeclarations()) {
            if (!(body instanceof MethodDeclaration md)) continue;
            if (!md.getName().getIdentifier().equals(wantName)) continue;
            if (md.parameters().size() != wantArity) continue;
            if (arityMatch == null) arityMatch = md;
            if (paramsMatch(md, wantParams)) return md;
        }
        return arityMatch;
    }

    private static ASTNode findField(AbstractTypeDeclaration enclosing, IVariableBinding vb) {
        String want = vb.getName();
        for (Object body : enclosing.bodyDeclarations()) {
            if (body instanceof FieldDeclaration fd) {
                for (Object frag : fd.fragments()) {
                    if (frag instanceof VariableDeclarationFragment vdf
                            && vdf.getName().getIdentifier().equals(want)) {
                        return vdf;
                    }
                }
            } else if (body instanceof EnumConstantDeclaration ecd
                    && ecd.getName().getIdentifier().equals(want)) {
                return ecd;
            }
        }
        return null;
    }

    private static List<String> erasedSimpleParamNames(IMethodBinding mb) {
        var types = mb.getParameterTypes();
        var out = new ArrayList<String>(types.length);
        for (var t : types) {
            ITypeBinding erasure = t.getErasure();
            ITypeBinding effective = erasure != null ? erasure : t;
            out.add(effective.getName()); // simple name, with [] for arrays
        }
        return out;
    }

    private static boolean paramsMatch(MethodDeclaration md, List<String> want) {
        var params = md.parameters();
        if (params.size() != want.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            String have = params.get(i).toString();
            // Strip annotations and "final" modifier; keep type + name; collapse
            // generics so List<String> compares as List.
            String type = stripToType(have);
            if (!simpleNameMatches(type, want.get(i))) return false;
        }
        return true;
    }

    private static String stripToType(String paramSource) {
        // SingleVariableDeclaration.toString() looks like "final List<String> arg".
        // We want the type token, generics stripped, modifiers removed.
        String s = paramSource;
        int lt = s.indexOf('<');
        while (lt >= 0) {
            int depth = 1;
            int i = lt + 1;
            while (i < s.length() && depth > 0) {
                char c = s.charAt(i++);
                if (c == '<') depth++;
                else if (c == '>') depth--;
            }
            s = s.substring(0, lt) + s.substring(i);
            lt = s.indexOf('<');
        }
        // Drop modifiers and identifier; keep last whitespace-separated token
        // that isn't a name (i.e. the type). Heuristic: rightmost token is the
        // parameter name; the token before it is the type (or `type[]` etc).
        String[] toks = s.trim().split("\\s+");
        if (toks.length < 2) return toks.length == 1 ? toks[0] : "";
        return toks[toks.length - 2];
    }

    private static boolean simpleNameMatches(String sourceType, String erasureSimple) {
        // sourceType may be qualified ("java.lang.String") or simple ("String"),
        // and may carry "[]" or "..." for arrays/varargs.
        String src = sourceType.replace("...", "[]");
        String want = erasureSimple;
        // Reduce to simple type name.
        int lastDot = src.lastIndexOf('.');
        if (lastDot >= 0) src = src.substring(lastDot + 1);
        return src.equals(want);
    }

    // ---- name-offset extraction --------------------------------------------

    private static Span nameSpan(ASTNode node) {
        return switch (node) {
            case TypeDeclaration td -> Span.of(td.getName());
            case EnumDeclaration ed -> Span.of(ed.getName());
            case RecordDeclaration rd -> Span.of(rd.getName());
            case AnnotationTypeDeclaration atd -> Span.of(atd.getName());
            case MethodDeclaration md -> Span.of(md.getName());
            case EnumConstantDeclaration ecd -> Span.of(ecd.getName());
            case VariableDeclarationFragment vdf -> Span.of(vdf.getName());
            default -> new Span(node.getStartPosition(), Math.min(node.getLength(), 1));
        };
    }

    private record Span(int start, int length) {
        static Span of(org.eclipse.jdt.core.dom.SimpleName n) {
            return new Span(n.getStartPosition(), n.getLength());
        }
    }

    // ---- parsing -----------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static CompilationUnit parse(Path sourceFile) {
        try {
            char[] content = Files.readString(sourceFile, StandardCharsets.UTF_8).toCharArray();
            var parser = ASTParser.newParser(AST.JLS_Latest);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            String latest = JavaCore.latestSupportedJavaVersion();
            parser.setCompilerOptions(java.util.Map.of(
                    JavaCore.COMPILER_SOURCE, latest,
                    JavaCore.COMPILER_COMPLIANCE, latest,
                    JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, latest));
            parser.setSource(content);
            parser.setResolveBindings(false);
            parser.setStatementsRecovery(true);
            parser.setUnitName("/" + sourceFile.getFileName());
            return (CompilationUnit) parser.createAST(null);
        } catch (IOException e) {
            return null;
        }
    }
}
