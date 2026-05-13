package com.almato.bromo.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.almato.bromo.symbol.Descriptor;
import com.almato.bromo.symbol.SymbolIndex;
import com.almato.bromo.symbol.SymbolKind;
import com.almato.bromo.util.CancelToken;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class CompletionFeatureTest {

    private static final URI DUMMY = URI.create("file:///x/X.java");

    @Test
    @DisplayName("identifier prefix produces matching items from the index")
    void prefixCompletion() {
        var idx = new SymbolIndex();
        idx.add(cls("PieceTable",        "x.PieceTable"));
        idx.add(cls("PointerDispatcher", "x.PointerDispatcher"));
        idx.add(cls("String",            "java.lang.String"));
        var feature = new CompletionFeature(idx);

        var src = "class A { Pie";
        var result = feature.completionsAt(DUMMY, src, src.length(), CancelToken.never());
        var labels = result.items().stream().map(CompletionItem::label).toList();

        assertEquals(1, result.items().size(), "expected one match for 'Pie'");
        assertTrue(labels.contains("PieceTable"));
    }

    @Test
    @DisplayName("empty prefix returns no items")
    void emptyPrefixYieldsNothing() {
        var idx = new SymbolIndex();
        idx.add(cls("Foo", "Foo"));
        var feature = new CompletionFeature(idx);

        var result = feature.completionsAt(DUMMY, "class A {  ", 11, CancelToken.never());
        assertTrue(result.items().isEmpty(), "expected no items for whitespace cursor");
    }

    @Test
    @DisplayName("detail is the signature when available, FQN otherwise")
    void detailRendering() {
        var idx = new SymbolIndex();
        idx.add(new Descriptor(SymbolKind.METHOD, "x.A.doubled", "doubled",
                "doubled(int) : int", Path.of("X.java"), 0, 7));
        idx.add(new Descriptor(SymbolKind.CLASS, "x.MyType", "MyType",
                null, Path.of("X.java"), 0, 6));
        var feature = new CompletionFeature(idx);

        var src1 = "doub";
        var r1 = feature.completionsAt(DUMMY, src1, src1.length(), CancelToken.never());
        assertEquals("doubled(int) : int", r1.items().get(0).detail());

        var src2 = "MyTy";
        var r2 = feature.completionsAt(DUMMY, src2, src2.length(), CancelToken.never());
        assertEquals("x.MyType", r2.items().get(0).detail());
    }

    @Test
    @DisplayName("results above the cap mark the result as incomplete")
    void incompleteSignal() {
        var idx = new SymbolIndex();
        for (int i = 0; i < 60; i++) {
            idx.add(cls("Foo" + String.format("%02d", i), "x.Foo" + i));
        }
        var feature = new CompletionFeature(idx);

        var src = "Foo";
        var result = feature.completionsAt(DUMMY, src, src.length(), CancelToken.never());
        assertEquals(50, result.items().size());
        assertTrue(result.isIncomplete(), "expected isIncomplete=true when over the cap");
    }

    private static Descriptor cls(String name, String fqn) {
        return new Descriptor(SymbolKind.CLASS, fqn, name, null, Path.of("X.java"), 0, name.length());
    }
}
