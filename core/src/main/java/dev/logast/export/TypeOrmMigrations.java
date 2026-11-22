package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #222: a Node.js repo's TypeORM migrations: classes implementing {@code MigrationInterface} whose {@code up()} runs SQL
 * through {@code queryRunner.query(…)}. The SQL (string or template literals, the first argument of each call) is
 * written as Flyway scripts in TypeORM's order (the timestamp in the file or class name), each statement on the line
 * it has in the migration file, so the export runs them in PostgreSQL like a Java service's migrations and its tables
 * cite the {@code .ts} file and line. Migrations that build schema with TypeORM's API instead of SQL are not read.
 */
final class TypeOrmMigrations {
    private static final Set<String> SKIP = Set.of("node_modules", "dist", "build", ".git", "coverage", "out", ".next");
    private static final Pattern TIMESTAMP = Pattern.compile("(\\d{10,})");
    private static final Pattern QUERY = Pattern.compile("\\.query\\s*\\(\\s*");

    /** A migration: its file (relative to the repo), its order, and its {@code up()}'s SQL statements with their lines. */
    record Migration(Path file, String rel, long order, List<Statement> statements) {
    }

    record Statement(String sql, int line) {
    }

    private TypeOrmMigrations() {
    }

    /** A folder with a {@code package.json} that depends on TypeORM. */
    static boolean applies(Path dir) {
        Path pkg = dir.resolve("package.json");
        if (!Files.isRegularFile(pkg)) {
            return false;
        }
        try {
            JsonNode n = new ObjectMapper().readTree(pkg.toFile());
            return n.path("dependencies").has("typeorm") || n.path("devDependencies").has("typeorm")
                    || n.path("dependencies").has("@nestjs/typeorm");
        } catch (IOException e) {
            return false;
        }
    }

    /** The package's name ({@code package.json}), else the folder's. */
    static String serviceName(Path dir) {
        try {
            String name = new ObjectMapper().readTree(dir.resolve("package.json").toFile()).path("name").asText("");
            return name.isBlank() ? dir.getFileName().toString() : name.replaceFirst("^@[^/]+/", "");
        } catch (IOException e) {
            return dir.getFileName().toString();
        }
    }

    /** The repo's migrations, in TypeORM's order. */
    static List<Migration> find(Path root) {
        List<Migration> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        walk(root, files);
        for (Path f : files) {
            String text;
            try {
                text = Files.readString(f);
            } catch (IOException e) {
                continue;
            }
            if (!text.contains("MigrationInterface") || !text.contains("up(")) {
                continue;
            }
            Matcher ts = TIMESTAMP.matcher(f.getFileName().toString());
            long order = ts.find() ? Long.parseLong(ts.group(1)) : Long.MAX_VALUE;
            List<Statement> sql = statements(text);
            if (!sql.isEmpty()) {
                out.add(new Migration(f, root.relativize(f).toString().replace('\\', '/'), order, sql));
            }
        }
        out.sort(Comparator.comparingLong(Migration::order).thenComparing(Migration::rel));
        return out;
    }

    private static void walk(Path dir, List<Path> out) {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) && !SKIP.contains(n) && !Files.isSymbolicLink(p)) {
                    walk(p, out);
                } else if ((n.endsWith(".ts") || n.endsWith(".js")) && !n.endsWith(".d.ts") && !n.contains(".spec.") && !n.contains(".test.")) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            // an unreadable folder: nothing in it
        }
    }

    /** The SQL of {@code up()}: the first argument of each {@code .query(…)} that is a string or template literal. */
    static List<Statement> statements(String text) {
        int up = text.indexOf("up(");
        int down = text.indexOf("down(", up);
        String body = down < 0 ? text.substring(up) : text.substring(up, down);
        List<Statement> out = new ArrayList<>();
        Matcher q = QUERY.matcher(body);
        while (q.find()) {
            int start = q.end();
            if (start >= body.length()) {
                break;
            }
            char quote = body.charAt(start);
            if (quote != '`' && quote != '\'' && quote != '"') {
                continue;   // SQL built elsewhere: not read
            }
            int end = start + 1;
            while (end < body.length() && body.charAt(end) != quote) {
                end += body.charAt(end) == '\\' ? 2 : 1;
            }
            if (end >= body.length()) {
                break;
            }
            String sql = body.substring(start + 1, end);
            int line = (int) text.substring(0, up + start + 1).chars().filter(c -> c == '\n').count() + 1;
            out.add(new Statement(sql, line));
        }
        return out;
    }

    /**
     * The migrations as Flyway scripts in {@code dir} ({@code V<n>__<name>.sql}), each statement on its line in the
     * migration file; script → the migration file it stands for.
     */
    static Map<Path, String> scripts(List<Migration> migrations, Path dir) {
        Map<Path, String> out = new LinkedHashMap<>();
        int n = 0;
        for (Migration m : migrations) {
            n++;
            List<String> lines = new ArrayList<>();
            for (Statement s : m.statements()) {
                String[] sl = (s.sql().strip().endsWith(";") ? s.sql() : s.sql() + ";").split("\n", -1);
                while (lines.size() < s.line() - 1) {
                    lines.add("");
                }
                for (int i = 0; i < sl.length; i++) {
                    int at = s.line() - 1 + i;
                    while (lines.size() <= at) {
                        lines.add("");
                    }
                    lines.set(at, lines.get(at).isEmpty() ? sl[i] : lines.get(at) + " " + sl[i]);
                }
            }
            String name = m.file().getFileName().toString().replaceAll("\\.(ts|js)$", "").replaceAll("[^A-Za-z0-9]+", "_");
            Path f = dir.resolve("V" + n + "__" + name + ".sql");
            try {
                Files.writeString(f, String.join("\n", lines) + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            out.put(f, m.rel());
        }
        return out;
    }
}
