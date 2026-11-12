package dev.logast.parse;

import org.openrewrite.Tree;
import org.openrewrite.marker.Range;

/** Source positions, read from the {@link Range} markers added by {@code UpdateSourcePositions}. */
public final class Pos {
    private Pos() {
    }

    public static Range range(Tree t) {
        return t == null ? null : t.getMarkers().findFirst(Range.class).orElse(null);
    }

    /** 1-based line, or -1 when unknown. */
    public static int line(Tree t) {
        Range r = range(t);
        return r == null ? -1 : r.getStart().getLine();
    }

    /** Offset of the first character of the element (prefix whitespace excluded). */
    public static int start(Tree t) {
        Range r = range(t);
        return r == null ? -1 : r.getStart().getOffset();
    }

    /** Offset just past the last character of the element. */
    public static int end(Tree t) {
        Range r = range(t);
        return r == null ? -1 : r.getEnd().getOffset();
    }
}
