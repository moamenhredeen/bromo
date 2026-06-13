package me.moamenhredeen.bromo.compiler;

import java.net.URI;
import org.eclipse.jdt.core.dom.ASTNode;

/// A declaration resolved from a source attachment.
///
/// Captures everything callers need to render the declaration: the
/// extracted file's [#sourceUri] (so a goto-def can point at it), the
/// declaring [#node] inside that file, and the raw [#sourceContent] so
/// callers can pull javadoc, enclosing decorations, or context around
/// the node without re-reading the file.
///
/// Shared by [me.moamenhredeen.bromo.features.DefinitionFeature] (uses the
/// node's name span for the jump target) and
/// [me.moamenhredeen.bromo.features.HoverFeature] (walks to the enclosing
/// body declaration for javadoc).
public record ResolvedDeclaration(URI sourceUri, ASTNode node, char[] sourceContent) {
}
