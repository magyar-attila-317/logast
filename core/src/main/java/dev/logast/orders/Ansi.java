package dev.logast.orders;

/**
 * Terminal colours for human output. Off by default (tests, pipes, files, LLM tools); the CLI switches it on for an
 * interactive terminal unless {@code NO_COLOR} is set ({@code LOGAST_COLOR=always|never} forces it).
 */
public final class Ansi {
    private static boolean enabled;

    private Ansi() {
    }

    public static void enable(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    private static String wrap(String code, String s) {
        return enabled ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    public static String bold(String s) {
        return wrap("1", s);
    }

    public static String dim(String s) {
        return wrap("2", s);
    }

    public static String red(String s) {
        return wrap("31", s);
    }

    public static String green(String s) {
        return wrap("32", s);
    }

    public static String yellow(String s) {
        return wrap("33", s);
    }

    public static String blue(String s) {
        return wrap("34", s);
    }

    public static String magenta(String s) {
        return wrap("35", s);
    }

    public static String cyan(String s) {
        return wrap("36", s);
    }

    public static String severity(String sev) {
        return switch (sev) {
            case "high" -> red(sev);
            case "medium" -> yellow(sev);
            default -> dim(sev);
        };
    }

    /** AUTO / NEEDS TEXT / REPORT-ONLY. */
    public static String kind(String kind) {
        return switch (kind) {
            case "AUTO" -> green(kind);
            case "NEEDS TEXT" -> cyan(kind);
            default -> magenta(kind);
        };
    }

    public static String ok() {
        return green("✓");
    }

    public static String warn(String s) {
        return yellow((enabled ? "! " : "WARNING: ") + s);
    }
}
