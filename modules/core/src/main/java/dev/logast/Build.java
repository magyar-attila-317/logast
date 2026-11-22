package dev.logast;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** #195: the exact logast build running: version and commit ("0.2.0 (v0.2.0-12-gabc1234)"); "dev" in tests. */
public final class Build {
    private static volatile String id;

    private Build() {
    }

    public static String id() {
        String i = id;
        if (i == null) {
            Properties p = new Properties();
            try (InputStream in = Build.class.getResourceAsStream("/logast-version.properties")) {
                if (in != null) {
                    p.load(in);
                }
            } catch (IOException e) {
                // no version file: a development build
            }
            i = p.getProperty("version", "dev") + " (" + p.getProperty("commit", "unknown") + ")";
            id = i;
        }
        return i;
    }

    /** Tests: pretend to be another build (null: the real one again). */
    static void set(String build) {
        id = build;
    }
}
