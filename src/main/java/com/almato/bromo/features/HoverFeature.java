package com.almato.bromo.features;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.util.CancelToken;
import com.almato.bromo.workspace.FileStore;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;

/// Resolves an LSP `textDocument/hover` request.
///
/// Parses the file with bindings, finds the AST node at the cursor, resolves
/// its binding, and renders:
/// - modifiers (`public static final` …)
/// - the signature (with **parameter names** when the declaring method is in
///   the same compilation unit — cross-file params still show types only)
/// - return type / field type / supertypes
/// - the source-extracted **javadoc** (markdown-style `///` or block `/** */`)
public final class HoverFeature {

    private final EcjContext ecj;
    private final FileStore files;

    public HoverFeature(EcjContext ecj, FileStore files) {
        this.ecj = ecj;
        this.files = files;
    }

    public Optional<HoverResult> hover(URI uri, int offset, CancelToken cancel) {
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

        String markdown = render(binding, cu, content);

        return Optional.of(new HoverResult(
                markdown,
                node.getStartPosition(),
                node.getStartPosition() + node.getLength()));
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
        if (node instanceof Name name) {
            var b = name.resolveBinding();
            if (b != null) return b;
        }
        if (node instanceof Type t) {
            var b = t.resolveBinding();
            if (b != null) return b;
        }
        ASTNode parent = node.getParent();
        if (parent instanceof Name name) return name.resolveBinding();
        if (parent instanceof Type t) return t.resolveBinding();
        return null;
    }

    // ---- render ------------------------------------------------------------

    private static String render(IBinding binding, CompilationUnit cu, char[] source) {
        ASTNode declaring = cu.findDeclaringNode(binding);
        var sb = new StringBuilder();
        sb.append("```java\n");
        switch (binding) {
            case ITypeBinding tb     -> renderType(sb, tb);
            case IMethodBinding mb   -> renderMethod(sb, mb, declaring);
            case IVariableBinding vb -> renderVariable(sb, vb);
            case IPackageBinding pb  -> sb.append("package ").append(pb.getName());
            default                  -> sb.append(binding.toString());
        }
        sb.append("\n```");

        if (declaring instanceof BodyDeclaration bd && bd.getJavadoc() != null) {
            String doc = extractJavadoc(bd.getJavadoc(), source);
            if (!doc.isBlank()) {
                sb.append("\n\n").append(doc);
            }
        }
        return sb.toString();
    }

    private static void renderModifiers(StringBuilder sb, int mods) {
        if (Modifier.isPublic(mods))        sb.append("public ");
        else if (Modifier.isProtected(mods))sb.append("protected ");
        else if (Modifier.isPrivate(mods))  sb.append("private ");
        if (Modifier.isStatic(mods))        sb.append("static ");
        if (Modifier.isFinal(mods))         sb.append("final ");
        if (Modifier.isAbstract(mods))      sb.append("abstract ");
        if (Modifier.isSynchronized(mods))  sb.append("synchronized ");
        if (Modifier.isVolatile(mods))      sb.append("volatile ");
        if (Modifier.isNative(mods))        sb.append("native ");
        if (Modifier.isTransient(mods))     sb.append("transient ");
        if (Modifier.isDefault(mods))       sb.append("default ");
    }

    private static void renderType(StringBuilder sb, ITypeBinding tb) {
        renderModifiers(sb, tb.getModifiers());
        if (tb.isInterface())       sb.append("interface ");
        else if (tb.isEnum())       sb.append("enum ");
        else if (tb.isRecord())     sb.append("record ");
        else if (tb.isAnnotation()) sb.append("@interface ");
        else                        sb.append("class ");
        String qn = tb.getQualifiedName();
        sb.append(qn.isEmpty() ? tb.getName() : qn);

        ITypeBinding sup = tb.getSuperclass();
        if (sup != null
                && !"java.lang.Object".equals(sup.getQualifiedName())
                && !"java.lang.Record".equals(sup.getQualifiedName())
                && !"java.lang.Enum".equals(sup.getQualifiedName())) {
            sb.append("\n    extends ").append(simpleName(sup));
        }
        ITypeBinding[] ifaces = tb.getInterfaces();
        if (ifaces.length > 0) {
            sb.append("\n    implements ");
            for (int i = 0; i < ifaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleName(ifaces[i]));
            }
        }
    }

    private static void renderMethod(StringBuilder sb, IMethodBinding mb, ASTNode declaring) {
        renderModifiers(sb, mb.getModifiers());
        if (!mb.isConstructor() && mb.getReturnType() != null) {
            sb.append(simpleName(mb.getReturnType())).append(' ');
        }
        if (mb.getDeclaringClass() != null) {
            sb.append(mb.getDeclaringClass().getName()).append('.');
        }
        sb.append(mb.getName()).append('(');

        ITypeBinding[] paramTypes = mb.getParameterTypes();
        String[] paramNames = parameterNames(declaring, paramTypes.length);
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simpleName(paramTypes[i]));
            if (paramNames[i] != null) {
                sb.append(' ').append(paramNames[i]);
            }
        }
        sb.append(')');

        ITypeBinding[] thrown = mb.getExceptionTypes();
        if (thrown.length > 0) {
            sb.append("\n    throws ");
            for (int i = 0; i < thrown.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleName(thrown[i]));
            }
        }
    }

    private static void renderVariable(StringBuilder sb, IVariableBinding vb) {
        renderModifiers(sb, vb.getModifiers());
        sb.append(simpleName(vb.getType())).append(' ');
        if (vb.isField() && vb.getDeclaringClass() != null) {
            sb.append(vb.getDeclaringClass().getName()).append('.');
        }
        sb.append(vb.getName());
    }

    private static String simpleName(ITypeBinding tb) {
        String name = tb.getName();
        return name == null || name.isEmpty() ? tb.getQualifiedName() : name;
    }

    private static String[] parameterNames(ASTNode declaring, int len) {
        var names = new String[len];
        if (declaring instanceof MethodDeclaration md) {
            @SuppressWarnings("unchecked")
            List<SingleVariableDeclaration> params = md.parameters();
            for (int i = 0; i < Math.min(len, params.size()); i++) {
                names[i] = params.get(i).getName().getIdentifier();
            }
        }
        return names;
    }

    // ---- javadoc extraction ------------------------------------------------

    private static String extractJavadoc(Javadoc jd, char[] source) {
        int start = jd.getStartPosition();
        int len = jd.getLength();
        if (start < 0 || len <= 0 || start + len > source.length) return "";
        return stripJavadocDelimiters(new String(source, start, len));
    }

    /// Strips `/** */`, leading `*`, and `///` markers; returns the markdown
    /// body. Multi-line javadoc is preserved as-is so JEP 467 markdown comes
    /// through cleanly.
    private static String stripJavadocDelimiters(String raw) {
        var out = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.equals("/**") || trimmed.equals("*/")) continue;
            if (trimmed.startsWith("/**")) trimmed = trimmed.substring(3);
            if (trimmed.endsWith("*/"))    trimmed = trimmed.substring(0, trimmed.length() - 2);
            trimmed = trimmed.strip();
            if (trimmed.startsWith("* "))  trimmed = trimmed.substring(2);
            else if (trimmed.equals("*"))  trimmed = "";
            else if (trimmed.startsWith("/// ")) trimmed = trimmed.substring(4);
            else if (trimmed.startsWith("///"))  trimmed = trimmed.substring(3);
            out.append(trimmed).append('\n');
        }
        return out.toString().strip();
    }
}
