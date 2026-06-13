package me.moamenhredeen.bromo.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SymbolIndexTest {

    private static Descriptor cls(String fqn, String name) {
        return new Descriptor(SymbolKind.CLASS, fqn, name, null, Path.of("X.java"), 0, name.length());
    }

    @Test
    @DisplayName("findExact returns descriptors keyed by simple name")
    void exactLookup() {
        var idx = new SymbolIndex();
        idx.add(cls("a.b.Foo", "Foo"));
        idx.add(cls("c.d.Foo", "Foo"));
        idx.add(cls("a.b.Bar", "Bar"));
        var foos = idx.findExact("Foo");
        assertEquals(2, foos.size());
        assertTrue(foos.stream().anyMatch(d -> d.fqn().equals("a.b.Foo")));
        assertTrue(foos.stream().anyMatch(d -> d.fqn().equals("c.d.Foo")));
    }

    @Test
    @DisplayName("findByPrefix returns matches in ascending name order, respecting limit")
    void prefixLookup() {
        var idx = new SymbolIndex();
        idx.add(cls("x.Apple",     "Apple"));
        idx.add(cls("x.Application","Application"));
        idx.add(cls("x.Apex",      "Apex"));
        idx.add(cls("x.Banana",    "Banana"));

        var byApp = idx.findByPrefix("Ap", 10);
        assertEquals(3, byApp.size());
        assertEquals(List.of("Apex", "Apple", "Application"),
                byApp.stream().map(Descriptor::name).toList());

        var limited = idx.findByPrefix("Ap", 2);
        assertEquals(2, limited.size());
    }

    @Test
    @DisplayName("size() returns total descriptor count")
    void sizeReflectsAllDescriptors() {
        var idx = new SymbolIndex();
        idx.addAll(List.of(cls("x.A", "A"), cls("x.B", "B"), cls("y.A", "A")));
        assertEquals(3, idx.size());
    }

    @Test
    @DisplayName("clear empties the index")
    void clear() {
        var idx = new SymbolIndex();
        idx.add(cls("x.A", "A"));
        idx.clear();
        assertEquals(0, idx.size());
        assertTrue(idx.findExact("A").isEmpty());
    }
}
