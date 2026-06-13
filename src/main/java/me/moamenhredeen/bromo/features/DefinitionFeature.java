package me.moamenhredeen.bromo.features;

import me.moamenhredeen.bromo.compiler.EcjContext;
import me.moamenhredeen.bromo.compiler.SourceResolver;
import me.moamenhredeen.bromo.query.QueryEngine;
import me.moamenhredeen.bromo.symbol.SymbolIndex;
import me.moamenhredeen.bromo.symbol.SymbolKind;
import me.moamenhredeen.bromo.util.CancelToken;
import me.moamenhredeen.bromo.workspace.FileStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/// Resolves an LSP `textDocument/definition` request.
///
/// Three-stage strategy:
/// 1. **Fast in-file path**: parse the current file with bindings, find the
///    AST node at the cursor, resolve its binding, and look the declaration
///    up locally via `CompilationUnit#findDeclaringNode`.
/// 2. **Workspace fallback (source bindings)**: when the binding is from
///    source but declared elsewhere in the workspace, look it up in the
///    [SymbolIndex] by simple name + kind. Refined to exact bindings at M6.5+.
/// 3. **Source attachment (binary bindings)**: when the binding points at
///    a binary classpath entry — the JDK or a library jar — hand off to
///    [SourceResolver], which materialises the attached source and walks
///    the AST to land on the declaration. This is the same pattern Eclipse
///    JDT and IntelliJ use; it's what lets goto-def into `String#length`
///    actually work.
public final class DefinitionFeature {

    private final EcjContext ecj;
    private final FileStore files;
    private final SymbolIndex symbols;
    private final SourceResolver sources;
    private final QueryEngine queries;

    public DefinitionFeature(EcjContext ecj, FileStore files, SymbolIndex symbols, SourceResolver sources) {
        this(ecj, files, symbols, sources, null);
    }

    public DefinitionFeature(EcjContext ecj, FileStore files, SymbolIndex symbols,
                             SourceResolver sources, QueryEngine queries) {
        this.ecj = ecj;
        this.files = files;
        this.symbols = symbols;
        this.sources = sources;
        this.queries = queries;
    }

    public Optional<DefinitionResult> definition(URI uri, int offset, CancelToken cancel) {
        if (cancel.isCancelled()) return Optional.empty();

        CompilationUnit cu;
        if (queries != null) {
            var hit = queries.cachedParsedAst(uri);
            if (hit.isPresent()) {
                cu = hit.get();
            } else {
                char[] content = readContent(uri).orElse(null);
                if (content == null) return Optional.empty();
                cu = ecj.parseWithBindings(uri, content);
            }
        } else {
            char[] content = readContent(uri).orElse(null);
            if (content == null) return Optional.empty();
            cu = ecj.parseWithBindings(uri, content);
        }
        if (cu == null) return Optional.empty();
        if (cancel.isCancelled()) return Optional.empty();

        ASTNode node = NodeFinder.perform(cu, offset, 0);
        if (node == null) return Optional.empty();

        IBinding binding = resolveBinding(node);
        if (binding == null) return Optional.empty();

        // Fast path: declaration lives in this compilation unit.
        ASTNode declaringNode = cu.findDeclaringNode(binding);
        if (declaringNode != null) {
            ASTNode nameNode = nameOf(declaringNode);
            return Optional.of(new DefinitionResult(
                    uri,
                    nameNode.getStartPosition(),
                    nameNode.getStartPosition() + nameNode.getLength()));
        }

        // Source-attachment path for binary bindings (JDK + library jars).
        // Take this branch first: a `String` reference has a binary binding
        // whose simple name might collide with a workspace class.
        if (isFromBinary(binding)) {
            var bin = sources.resolve(binding);
            if (bin.isPresent()) return bin;
        }

        // Workspace fallback: source-defined declaration in another CU.
        return crossFileLookup(binding);
    }

    private static boolean isFromBinary(IBinding binding) {
        return switch (binding) {
            case ITypeBinding tb -> !tb.isFromSource();
            case IMethodBinding mb -> {
                var owner = mb.getDeclaringClass();
                yield owner != null && !owner.isFromSource();
            }
            case IVariableBinding vb -> {
                var owner = vb.getDeclaringClass();
                yield owner != null && !owner.isFromSource();
            }
            default -> false;
        };
    }

    private Optional<DefinitionResult> crossFileLookup(IBinding binding) {
        String simpleName = simpleNameOf(binding);
        if (simpleName == null) return Optional.empty();
        for (var d : symbols.findExact(simpleName)) {
            if (kindMatches(binding, d.kind())) {
                return Optional.of(new DefinitionResult(
                        d.source().toUri(),
                        d.offset(),
                        d.offset() + d.length()));
            }
        }
        return Optional.empty();
    }

    private Optional<char[]> readContent(URI uri) {
        var open = files.getOpen(uri);
        if (open.isPresent()) {
            return Optional.of(open.get().text().toCharArray());
        }
        try {
            return Optional.of(Files.readString(Paths.get(uri), StandardCharsets.UTF_8).toCharArray());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static IBinding resolveBinding(ASTNode node) {
        if (node instanceof Name n) {
            var b = n.resolveBinding();
            if (b != null) return b;
        }
        if (node instanceof Type t) {
            var b = t.resolveBinding();
            if (b != null) return b;
        }
        ASTNode parent = node.getParent();
        if (parent instanceof Name pn) return pn.resolveBinding();
        if (parent instanceof Type pt) return pt.resolveBinding();
        return null;
    }

    private static ASTNode nameOf(ASTNode declaringNode) {
        if (declaringNode instanceof TypeDeclaration td)         return td.getName();
        if (declaringNode instanceof EnumDeclaration ed)          return ed.getName();
        if (declaringNode instanceof RecordDeclaration rd)        return rd.getName();
        if (declaringNode instanceof MethodDeclaration md)        return md.getName();
        if (declaringNode instanceof VariableDeclarationFragment vdf) return vdf.getName();
        if (declaringNode instanceof EnumConstantDeclaration ecd) return ecd.getName();
        return declaringNode;
    }

    private static String simpleNameOf(IBinding binding) {
        return switch (binding) {
            case ITypeBinding tb     -> tb.getName();
            case IMethodBinding mb   -> mb.getName();
            case IVariableBinding vb -> vb.getName();
            default                  -> binding.getName();
        };
    }

    private static boolean kindMatches(IBinding binding, SymbolKind kind) {
        return switch (binding) {
            case ITypeBinding _ ->
                    kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                    || kind == SymbolKind.ENUM || kind == SymbolKind.RECORD
                    || kind == SymbolKind.ANNOTATION;
            case IMethodBinding mb ->
                    mb.isConstructor() ? kind == SymbolKind.CONSTRUCTOR : kind == SymbolKind.METHOD;
            case IVariableBinding vb -> {
                if (vb.isField() || vb.isEnumConstant()) yield kind == SymbolKind.FIELD;
                yield false; // locals don't live in the workspace index
            }
            default -> false;
        };
    }
}
