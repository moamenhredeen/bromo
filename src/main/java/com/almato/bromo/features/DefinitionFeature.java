package com.almato.bromo.features;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.symbol.SymbolIndex;
import com.almato.bromo.symbol.SymbolKind;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.FileStore;
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
/// Two-stage strategy:
/// 1. **Fast in-file path**: parse the current file with bindings, find the
///    AST node at the cursor, resolve its binding, and look the declaration
///    up locally via `CompilationUnit#findDeclaringNode`.
/// 2. **Cross-file fallback**: if the declaring node isn't in this CU, scan
///    the workspace [SymbolIndex] for a descriptor with a matching simple
///    name + kind. Returns the first match — adequate for v0, refined to
///    use exact bindings at M6.5+.
public final class DefinitionFeature {

    private final EcjContext ecj;
    private final FileStore files;
    private final SymbolIndex symbols;

    public DefinitionFeature(EcjContext ecj, FileStore files, SymbolIndex symbols) {
        this.ecj = ecj;
        this.files = files;
        this.symbols = symbols;
    }

    public Optional<DefinitionResult> definition(URI uri, int offset, CancelToken cancel) {
        char[] content = readContent(uri).orElse(null);
        if (content == null) return Optional.empty();
        if (cancel.isCancelled()) return Optional.empty();

        CompilationUnit cu = ecj.parseWithBindings(uri, content);
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

        // Fallback: workspace-wide symbol index by simple name + kind.
        return crossFileLookup(binding);
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
