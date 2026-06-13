package me.moamenhredeen.bromo.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/// Property-based: any random sequence of edits against a [PieceTable] must
/// produce the same content as the equivalent sequence against a reference
/// [StringBuilder].
final class PieceTablePropertyTest {

    @Property(tries = 500)
    void equivalentToReference(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long seed,
            @ForAll @IntRange(min = 0, max = 30) int initLen,
            @ForAll @IntRange(min = 1, max = 60) int ops) {

        var rnd = new Random(seed);

        var sb = new StringBuilder();
        for (int i = 0; i < initLen; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        var pt = new PieceTable(sb.toString());
        var ref = new StringBuilder(sb);

        for (int op = 0; op < ops; op++) {
            int kind = rnd.nextInt(3);
            int len = ref.length();
            if (kind == 0) {                                // insert
                int at = len == 0 ? 0 : rnd.nextInt(len + 1);
                var text = String.valueOf((char) ('a' + rnd.nextInt(26)));
                pt.insert(at, text);
                ref.insert(at, text);
            } else if (kind == 1 && len > 0) {              // delete
                int at = rnd.nextInt(len);
                int dl = 1 + rnd.nextInt(Math.min(5, len - at));
                pt.delete(at, dl);
                ref.delete(at, at + dl);
            } else if (kind == 2 && len > 0) {              // replace
                int at = rnd.nextInt(len);
                int dl = 1 + rnd.nextInt(Math.min(3, len - at));
                var text = String.valueOf((char) ('A' + rnd.nextInt(26)));
                pt.replace(at, dl, text);
                ref.replace(at, at + dl, text);
            }
        }

        assertEquals(ref.toString(), pt.text(),       "text() snapshot");
        assertEquals(ref.length(), pt.length(),       "length");
        for (int i = 0; i < ref.length(); i++) {
            assertEquals(ref.charAt(i), pt.charAt(i), "charAt " + i);
        }
        assertEquals(ref.toString(), pt.subSequence(0, pt.length()).toString(), "subSequence(0,len)");
    }
}
