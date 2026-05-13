package com.almato.bromo.features;

import com.almato.bromo.compiler.EcjContext;
import com.almato.bromo.util.CancelToken;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;

/// ECJ-backed member-access completion (`foo.|` / `Type.|` / `"foo".len|`).
///
/// Strategy (the classic "sentinel-reparse" trick — ECJ's own
/// `CompletionEngine` does something similar internally):
///
/// 1. Look back from the cursor to find a preceding `.` (skipping identifier
///    chars that form the partial typed prefix).
/// 2. Inject a synthetic identifier (`_bromoCmpl`) at the cursor so the
///    parser produces a recoverable AST even when the user has only typed
///    `foo.` and nothing after it.
/// 3. Parse with bindings via [EcjContext#parseWithBindings].
/// 4. Find the AST node enclosing our synthetic identifier and walk up to
///    the enclosing access expression (`FieldAccess`, `MethodInvocation`,
///    `QualifiedName`, `SuperFieldAccess`, `SuperMethodInvocation`).
/// 5. Resolve the receiver's `ITypeBinding`. Static vs instance is decided
///    by whether the receiver is itself a type reference.
/// 6. Walk the type hierarchy collecting methods + fields whose simple
///    name matches the partial typed prefix.
///
/// Returns [Optional#empty] if the cursor isn't in a member-access context
/// or if the receiver type can't be resolved; the caller decides whether
/// to fall through to another completion source.
public final class MemberCompletionResolver {

    private static final String SENTINEL = "_bromoCmpl";
    private static final int LIMIT = 50;

    private final EcjContext ecj;

    public MemberCompletionResolver(EcjContext ecj) {
        this.ecj = ecj;
    }

    /// `true` iff the cursor is positioned right after a `.` (allowing for a
    /// partial identifier between dot and cursor). Used by the higher-level
    /// completion router to know whether to even attempt this resolver vs
    /// the symbol-index prefix path.
    public static boolean isAfterDot(CharSequence content, int offset) {
        if (offset <= 0 || offset > content.length()) return false;
        int scanFrom = offset;
        while (scanFrom > 0 && Character.isJavaIdentifierPart(content.charAt(scanFrom - 1))) {
            scanFrom--;
        }
        return scanFrom > 0 && content.charAt(scanFrom - 1) == '.';
    }

    public Optional<CompletionResult> tryComplete(
            URI uri, CharSequence content, int offset, CancelToken cancel) {

        if (!isAfterDot(content, offset)) return Optional.empty();
        if (cancel.isCancelled())          return Optional.empty();

        int prefixStart = offset;
        while (prefixStart > 0 && Character.isJavaIdentifierPart(content.charAt(prefixStart - 1))) {
            prefixStart--;
        }
        String partial = content.subSequence(prefixStart, offset).toString();

        String modified = new StringBuilder(content.length() + SENTINEL.length())
                .append(content, 0, offset)
                .append(SENTINEL)
                .append(content, offset, content.length())
                .toString();

        CompilationUnit cu = ecj.parseWithBindings(uri, modified.toCharArray());
        if (cu == null) return Optional.empty();
        if (cancel.isCancelled()) return Optional.empty();

        ASTNode marker = NodeFinder.perform(cu, offset, SENTINEL.length());
        if (marker == null) return Optional.empty();

        Receiver receiver = findReceiver(marker);
        if (receiver == null) return Optional.empty();

        ITypeBinding type = resolveType(receiver.expression);
        if (type == null) return Optional.empty();
        if (cancel.isCancelled()) return Optional.empty();

        var items = new ArrayList<CompletionItem>();
        collectMembers(type, partial, receiver.wantStatic, items, new HashSet<>());
        boolean incomplete = items.size() > LIMIT;
        if (incomplete) items.subList(LIMIT, items.size()).clear();
        return Optional.of(new CompletionResult(items, incomplete));
    }

    // ---- AST navigation ----------------------------------------------------

    private static Receiver findReceiver(ASTNode marker) {
        for (ASTNode n = marker; n != null; n = n.getParent()) {
            if (n instanceof FieldAccess fa) {
                return new Receiver(fa.getExpression(), isTypeReference(fa.getExpression()));
            }
            if (n instanceof MethodInvocation mi && mi.getExpression() != null) {
                return new Receiver(mi.getExpression(), isTypeReference(mi.getExpression()));
            }
            if (n instanceof QualifiedName qn) {
                return new Receiver(qn.getQualifier(), isTypeReference(qn.getQualifier()));
            }
            if (n instanceof SuperFieldAccess) {
                return enclosingTypeReceiver(n);
            }
            if (n instanceof SuperMethodInvocation) {
                return enclosingTypeReceiver(n);
            }
        }
        return null;
    }

    private static Receiver enclosingTypeReceiver(ASTNode anchor) {
        for (ASTNode n = anchor; n != null; n = n.getParent()) {
            if (n instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration t) {
                var binding = t.resolveBinding();
                if (binding != null && binding.getSuperclass() != null) {
                    return new Receiver(null, false, binding.getSuperclass());
                }
                return null;
            }
        }
        return null;
    }

    private static boolean isTypeReference(Expression e) {
        if (e instanceof Name n) {
            IBinding binding = n.resolveBinding();
            return binding instanceof ITypeBinding;
        }
        return false;
    }

    private static ITypeBinding resolveType(Expression e) {
        if (e == null) return null;
        return e.resolveTypeBinding();
    }

    // ---- member enumeration ------------------------------------------------

    private static void collectMembers(
            ITypeBinding type,
            String prefix,
            boolean wantStatic,
            List<CompletionItem> out,
            Set<String> visitedKeys) {

        if (type == null) return;
        String key = type.getKey();
        if (key != null && !visitedKeys.add(key)) return;

        for (IMethodBinding method : type.getDeclaredMethods()) {
            if (method.isConstructor()) continue;
            if (!method.getName().startsWith(prefix)) continue;
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            if (wantStatic != isStatic) continue;
            out.add(new CompletionItem(
                    method.getName(),
                    CompletionItemKind.METHOD,
                    formatMethod(method)));
        }
        for (IVariableBinding field : type.getDeclaredFields()) {
            if (!field.getName().startsWith(prefix)) continue;
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            if (wantStatic != isStatic) continue;
            out.add(new CompletionItem(
                    field.getName(),
                    CompletionItemKind.FIELD,
                    formatField(field)));
        }

        // Inherited members.
        ITypeBinding superCls = type.getSuperclass();
        if (superCls != null) collectMembers(superCls, prefix, wantStatic, out, visitedKeys);
        for (ITypeBinding iface : type.getInterfaces()) {
            collectMembers(iface, prefix, wantStatic, out, visitedKeys);
        }
    }

    private static String formatMethod(IMethodBinding m) {
        var sb = new StringBuilder(m.getName()).append('(');
        boolean first = true;
        for (ITypeBinding p : m.getParameterTypes()) {
            if (!first) sb.append(", ");
            sb.append(p.getName());
            first = false;
        }
        sb.append(") : ");
        sb.append(m.getReturnType() != null ? m.getReturnType().getName() : "void");
        return sb.toString();
    }

    private static String formatField(IVariableBinding f) {
        return f.getType().getName() + " " + f.getName();
    }

    private record Receiver(Expression expression, boolean wantStatic, ITypeBinding override) {
        Receiver(Expression expression, boolean wantStatic) { this(expression, wantStatic, null); }
    }
}
