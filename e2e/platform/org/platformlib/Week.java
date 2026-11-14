package org.platformlib;

/**
 * Stands in for a company library the service depends on: e2e/before compiles against it, but logast never gets it
 * on its classpath (as when a copied classpath misses an internal jar). Its type stays unresolved in the analysis.
 */
public record Week(int year, int number) {
    public static Week current() {
        return new Week(2026, 1);
    }
}
