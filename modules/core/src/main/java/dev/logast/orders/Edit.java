package dev.logast.orders;

/**
 * A text edit against the scanned version of a file.
 *
 * @param kind  {@code replace} (start..end replaced by text; start == end inserts), {@code create}, {@code delete}, or
 *              {@code yaml-merge} (text is a YAML snippet merged into the file as it is when applied, created if missing;
 *              start 1 = the snippet's values replace existing ones), or {@code properties-merge} (key=value lines
 *              merged into the base document of a .properties file the same way). Merges never conflict with each other.
 */
public record Edit(String file, String kind, int start, int end, String text) {
    public static Edit replace(String file, int start, int end, String text) {
        return new Edit(file, "replace", start, end, text);
    }

    public static Edit insert(String file, int at, String text) {
        return new Edit(file, "replace", at, at, text);
    }

    public static Edit create(String file, String text) {
        return new Edit(file, "create", 0, 0, text);
    }

    public static Edit yamlMerge(String file, String snippet, boolean overwrite) {
        return new Edit(file, "yaml-merge", overwrite ? 1 : 0, overwrite ? 1 : 0, snippet);
    }

    public static Edit propertiesMerge(String file, String lines, boolean overwrite) {
        return new Edit(file, "properties-merge", overwrite ? 1 : 0, overwrite ? 1 : 0, lines);
    }

    public boolean merge() {
        return kind.equals("yaml-merge") || kind.equals("properties-merge");
    }

    public static Edit delete(String file) {
        return new Edit(file, "delete", 0, 0, "");
    }

    public boolean overlaps(Edit o) {
        if (merge() && o.merge()) {
            return false;   // merged one after the other at apply time
        }
        if (!file.equals(o.file) || !kind.equals("replace") || !o.kind.equals("replace")) {
            return file.equals(o.file) && !(kind.equals("replace") && o.kind.equals("replace"));
        }
        if (start == end || o.start == o.end) {
            // an insertion only conflicts when it lands strictly inside the other range
            int at = start == end ? start : o.start;
            Edit range = start == end ? o : this;
            return range.start < at && at < range.end;
        }
        return start < o.end && o.start < end;
    }
}
