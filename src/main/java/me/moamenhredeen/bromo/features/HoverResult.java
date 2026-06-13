package me.moamenhredeen.bromo.features;

/// Result of a hover query.
///
/// `markdown` is the body of the LSP `Hover.contents` (always rendered as
/// markdown). `startOffset` / `endOffset` are absolute char offsets into the
/// source; the LSP adapter converts them to a `Range`.
public record HoverResult(String markdown, int startOffset, int endOffset) {}
