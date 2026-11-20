package dev.logast.facts;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * #183: facts never carry secrets. A value written literally where a secret would be (an env var of a chart, a
 * property's default, a Terraform attribute, a table row) is replaced by {@link #REDACTED} when its name or its shape
 * says secret; what is obviously a test stub or a local default is kept (it is no secret, and redacting it would hide
 * useful facts).
 */
public final class Secrets {
    private Secrets() {
    }

    public static final String REDACTED = "<redacted: looks like a secret>";

    /** Names that say secret: PASSWORD, DB_PASS, API_KEY, CLIENT_SECRET, AUTH_TOKEN, connectionString, sasToken, … */
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(^|[._-])(pass(word|wd)?|pwd|secret|token|credentials?|"
            + "private[._-]?key|api[._-]?key|access[._-]?key|secret[._-]?key|account[._-]?key|signing[._-]?key|client[._-]?secret|"
            + "auth|bearer|connection[._-]?string|conn[._-]?str|sas|jwt|cookie[._-]?secret|encryption[._-]?key|salt|pin)($|[._-])"
            + "|(password|passwd|secret|token|apikey|accesskey|privatekey|connectionstring|clientsecret)");
    /** Names that only look like it: a key's id, a token's URL or lifetime, a password policy. */
    private static final Pattern HARMLESS_NAME = Pattern.compile("(?i)(_|\\.|-)?(url|uri|endpoint|host|path|file|location|ttl|"
            + "expiry|expiration|timeout|length|min|max|policy|enabled|header|name|id|type|alias|ref|version|count|seconds|"
            + "minutes|hours|days)$");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern PEM = Pattern.compile("-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE)-----");
    private static final Pattern URL_WITH_PASSWORD = Pattern.compile("[a-z][a-z0-9+.-]*://[^\\s/:@]+:([^\\s/@]+)@");
    private static final Pattern CONNECTION_STRING = Pattern.compile("(?i)(password|pwd|accountkey|sharedaccesskey|sharedaccesssignature|sig)=([^;&\\s]+)");
    private static final Pattern KNOWN_KEYS = Pattern.compile("AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|"
            + "xox[baprs]-[A-Za-z0-9-]{10,}|sk_(live|test)_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{35}");
    /** Values that are no secret: placeholders, references, local defaults, obvious stubs. */
    private static final Pattern STUB = Pattern.compile("(?i)^(test|testing|dummy|fake|mock|stub|example|sample|changeme|change[._-]?me|"
            + "password|passw0rd|secret|token|admin|root|guest|user|default|none|null|undefined|todo|tbd|xxx+|\\*+|"
            + "local|dev|development|demo|foo|bar|baz|qwerty|123456|12345678)$");
    private static final Pattern REFERENCE = Pattern.compile("^(\\$\\{.*}|#\\{.*}|\\{\\{.*}}|secret:.*|configmap:.*|k8s-secret:.*|field:.*|"
            + "env\\(.*\\)|vault:.*|<redacted.*)$");
    private static final Pattern LOCAL_URL = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://(localhost|127\\.[0-9.]+|0\\.0\\.0\\.0|\\[::1])([:/].*)?$");

    /** The value as facts may carry it: itself, or {@link #REDACTED} when a secret would be written. */
    public static String clean(String name, String value) {
        return looksSecret(name, value) ? REDACTED : value;
    }

    /** Whether {@code value} (named {@code name}, which may be null) is a secret written in plain text. */
    public static boolean looksSecret(String name, String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        if (v.isEmpty() || REFERENCE.matcher(v).matches()) {
            return false;
        }
        // by shape, whatever the name: the value itself is a credential
        if (JWT.matcher(v).find() || PEM.matcher(v).find() || KNOWN_KEYS.matcher(v).find()) {
            return true;
        }
        var url = URL_WITH_PASSWORD.matcher(v);
        if (url.find() && !stub(url.group(1))) {
            return true;
        }
        var cs = CONNECTION_STRING.matcher(v);
        if (cs.find() && !stub(cs.group(2))) {
            return true;
        }
        if (stub(v) || LOCAL_URL.matcher(v).matches()) {
            return false;
        }
        // by name: what it is called says secret, and the value is not an obvious stub
        if (name != null && SECRET_NAME.matcher(name).find() && !HARMLESS_NAME.matcher(name).find()) {
            return v.length() >= 4;
        }
        // a long random-looking token, whatever its name
        return v.length() >= 32 && !v.contains(" ") && !v.contains("/") && entropy(v) > 4.0;
    }

    private static boolean stub(String v) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.length() < 4 || STUB.matcher(s).matches() || s.startsWith("test") || s.startsWith("dummy") || s.startsWith("fake")
                || s.startsWith("example") || s.startsWith("changeme") || s.startsWith("mock") || REFERENCE.matcher(s).matches();
    }

    /** Shannon entropy in bits per character. */
    static double entropy(String s) {
        int[] counts = new int[128];
        int n = 0;
        for (char c : s.toCharArray()) {
            if (c < 128) {
                counts[c]++;
                n++;
            }
        }
        double h = 0;
        for (int c : counts) {
            if (c > 0) {
                double p = (double) c / n;
                h -= p * (Math.log(p) / Math.log(2));
            }
        }
        return h;
    }
}
