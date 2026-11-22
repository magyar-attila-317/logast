package dev.logast.model;

import java.util.Locale;

public enum Level {
    TRACE, DEBUG, INFO, WARN, ERROR;

    public static Level parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    public String method() {
        return name().toLowerCase(Locale.ROOT);
    }
}
