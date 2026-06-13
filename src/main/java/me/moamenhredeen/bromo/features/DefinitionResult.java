package me.moamenhredeen.bromo.features;

import java.net.URI;

/// Result of a goto-definition query — the location of the resolved declaration.
public record DefinitionResult(URI uri, int startOffset, int endOffset) {}
