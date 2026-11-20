package dev.logast.export;

import dev.logast.SpringProperties;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #157: the tables a service's Flyway migrations create, per environment, and the rows of the tables asked for, by
 * running the migrations in a throwaway PostgreSQL (Testcontainers): the SQL is run, not parsed. A migration folder
 * with a {@code common} subfolder and others means one environment per other subfolder ({@code common} + its own);
 * otherwise one run for all environments. Placeholders take their values from {@code spring.flyway.placeholders.*} of
 * the environment's profile over the base config; one with no value runs with its own name, and says so.
 */
public final class FlywayExtractor implements Extractor {
    public static final String ID = "flyway";
    public static final String DEFAULT_IMAGE = "postgres:16-alpine";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(?!flyway:)([^}]+)}");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)create\\s+(?:unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([\"\\w.${}]+)");
    private static final Pattern MIGRATION = Pattern.compile("(?i)^[VR]\\d*.*__.+\\.sql$|^R__.+\\.sql$");
    private static final Pattern SCHEMA_PLACEHOLDER = Pattern.compile("\\$\\{(?!flyway:)([^}]+)}\\s*\\.");
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("pg_catalog", "information_schema", "pg_toast");

    private final String service;
    private final List<String> rowTables;
    private final String image;
    private final List<String> problems = new ArrayList<>();
    private Set<String> lastDefaulted = Set.of();

    /**
     * @param service   the service's name (the Java extractor's)
     * @param rowTables tables whose rows become facts ({@code schema.name} or {@code name})
     * @param image     the PostgreSQL image (null: {@link #DEFAULT_IMAGE})
     */
    public FlywayExtractor(String service, List<String> rowTables, String image) {
        this.service = service;
        this.rowTables = rowTables == null ? List.of() : rowTables.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
    }

    @Override
    public String id() {
        return ID;
    }

    /** What went wrong per environment (a migration that failed): the export goes on with the others. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    private static String firstLine(Throwable e) {
        Throwable t = e;
        String msg = e.getMessage();
        while (t.getCause() != null) {
            t = t.getCause();
            if (t.getMessage() != null) {
                msg = t.getMessage();
            }
        }
        String script = e.getMessage() == null ? "" : e.getMessage().lines().filter(l -> l.contains("script")).findFirst().orElse("");
        return (script.isBlank() ? "" : script.strip() + ": ") + (msg == null ? e.toString() : msg.lines().findFirst().orElse(msg));
    }

    /** One run of the migrations: the environment (null: all) and its folders, in Flyway's order. */
    /**
     * @param schema    the schema to migrate into (null: as the service's config says)
     * @param perTenant the code chooses the schema at runtime (#179): {@code schema} is a stand-in
     * @param origin    the code that runs it, for runs the code makes; null for Spring Boot's
     */
    record Run(String env, List<Path> folders, String schema, boolean perTenant, String origin) {
        Run(String env, List<Path> folders) {
            this(env, folders, null, false, null);
        }
    }

    /** The runs of a service's migrations: per environment when the folder has {@code common} and others. */
    static List<Run> runs(Path root) {
        return runs(root, ClasspathMigrations.NONE);
    }

    static List<Run> runs(Path root, ClasspathMigrations cp) {
        // #180: profiles that set their own locations (application-prod.yml: common + prod) are the environments
        String base = SpringProperties.read(root).get("spring.flyway.locations");
        List<Run> perProfile = new ArrayList<>();
        for (String profile : profiles(root)) {
            String own = SpringProperties.forProfile(root, profile).get("spring.flyway.locations");
            if (own != null && !own.equals(base)) {
                List<Path> folders = locations(root, own, cp);
                if (!folders.isEmpty()) {
                    perProfile.add(new Run(profile, folders));
                }
            }
        }
        List<Run> out = new ArrayList<>();
        if (!perProfile.isEmpty()) {   // the base locations still run when no such profile is active
            List<Path> baseFolders = locations(root, SpringProperties.read(root).get("spring.flyway.locations"), cp);
            if (!baseFolders.isEmpty()) {
                out.add(new Run(null, baseFolders));
            }
            out.addAll(perProfile);
            return out;
        }
        for (Path b : locations(root, SpringProperties.read(root).get("spring.flyway.locations"), cp)) {
            out.addAll(split(b));
        }
        return out;
    }

    /** The profiles the service has config for: {@code application-<profile>.*} files and documents activated for one. */
    static List<String> profiles(Path root) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        Path resources = SpringProperties.appResources(root);
        if (resources == null) {
            return List.of();
        }
        for (Path dir : List.of(resources, resources.resolve("config"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> st = Files.list(dir)) {
                st.forEach(f -> {
                    Matcher m = Pattern.compile("application-([^.]+)\\.(ya?ml|properties)").matcher(f.getFileName().toString());
                    if (m.matches()) {
                        out.add(m.group(1));
                    } else if (f.getFileName().toString().matches("application\\.(ya?ml|properties)")) {
                        Matcher on = Pattern.compile("on-profile\\s*[:=]\\s*['\"]?([\\w.-]+)").matcher(read(f));
                        while (on.find()) {
                            out.add(on.group(1));
                        }
                    }
                });
            } catch (IOException e) {
                // no profiles from an unreadable folder
            }
        }
        return List.copyOf(out);
    }

    /** A migration folder's runs: one per environment subfolder (with {@code common}), else one. */
    static List<Run> split(Path base) {
        List<Run> out = new ArrayList<>();
        {
            List<Path> subs;
            try (Stream<Path> s = Files.list(base)) {
                subs = s.filter(Files::isDirectory).sorted().toList();
            } catch (IOException e) {
                return out;
            }
            Path common = base.resolve("common");
            List<Path> envs = subs.stream().filter(d -> !d.equals(common)).toList();
            if (Files.isDirectory(common) && !envs.isEmpty()) {
                for (Path env : envs) {
                    out.add(new Run(env.getFileName().toString(), List.of(common, env)));
                }
            } else {
                out.add(new Run(null, List.of(base)));
            }
        }
        return out;
    }

    /** {@code spring.flyway.locations} ({@code classpath:} and {@code filesystem:}), default {@code db/migration}. */
    static List<Path> locations(Path root) {
        return locations(root, SpringProperties.read(root).get("spring.flyway.locations"));
    }

    static List<Path> locations(Path root, String configured) {
        return locations(root, configured, ClasspathMigrations.NONE);
    }

    /**
     * The folders a {@code spring.flyway.locations} value names ({@code classpath:}, {@code filesystem:}, {@code {vendor}}):
     * a {@code classpath:} one in the service's resources and, as Flyway scans it at runtime, on its classpath (#181).
     */
    static List<Path> locations(Path root, String configured, ClasspathMigrations cp) {
        Path resources = SpringProperties.appResources(root);
        List<Path> out = new ArrayList<>();
        for (String loc : (configured == null || configured.isBlank() ? "classpath:db/migration" : configured).split(",")) {
            String l = loc.strip();
            if (l.contains("{vendor}")) {
                l = l.replace("{vendor}", "postgresql");
            }
            Path p = l.startsWith("filesystem:") ? root.resolve(l.substring("filesystem:".length()))
                    : resources == null ? null : resources.resolve(l.startsWith("classpath:") ? l.substring("classpath:".length()) : l);
            if (p != null && Files.isDirectory(p)) {
                out.add(p.normalize());
            }
            if (!l.startsWith("filesystem:")) {   // #181: a library's jar (or a module's class folder) may hold it too
                out.addAll(cp.folders(l.startsWith("classpath:") ? l.substring("classpath:".length()) : l,
                        resources == null ? null : resources.toAbsolutePath().normalize()));
            }
        }
        return out;
    }

    private List<ProgrammaticFlyway.CodeRun> codeRuns = List.of();
    private List<Path> classpath = List.of();
    private ClasspathMigrations cp = ClasspathMigrations.NONE;

    /** #181: the service's classpath, where a {@code classpath:} location may also be (a library's jar). */
    public FlywayExtractor withClasspath(List<Path> classpath) {
        this.classpath = classpath == null ? List.of() : classpath;
        return this;
    }

    /** #179: the Flyway runs the service's code makes (per tenant schema, for instance). */
    public FlywayExtractor withCodeRuns(List<ProgrammaticFlyway.CodeRun> runs) {
        this.codeRuns = runs;
        return this;
    }

    /** The code's runs whose location is not one Spring Boot already runs, as runs (split by environment like those). */
    List<Run> codeRuns(Path root) {
        List<Run> out = new ArrayList<>();
        java.util.Set<Path> done = new java.util.HashSet<>(locations(root, SpringProperties.read(root).get("spring.flyway.locations"), cp));
        for (ProgrammaticFlyway.CodeRun c : codeRuns) {
            String origin = c.file() + (c.line() > 0 ? ":" + c.line() : "");
            for (Path p : locations(root, c.location().strip(), cp)) {   // the service's own folder, and a library's (#181)
                if (!done.add(p.normalize())) {
                    continue;
                }
                String schema = c.schema() != null ? c.schema() : p.getFileName().toString();
                for (Run r : split(p.normalize())) {
                    out.add(new Run(r.env(), r.folders(), schema, c.schema() == null, origin));
                }
            }
        }
        return out;
    }

    @Override
    public void extract(Path root, FactSink out) {
        try (ClasspathMigrations lookup = new ClasspathMigrations(classpath)) {
            cp = lookup;
            extractRuns(root, out);
        } finally {
            cp = ClasspathMigrations.NONE;
        }
    }

    private void extractRuns(Path root, FactSink out) {
        List<Run> runs = new ArrayList<>(runs(root, cp));
        runs.addAll(codeRuns(root));
        if (runs.isEmpty()) {
            return;
        }
        try (PostgreSQLContainer pg = new PostgreSQLContainer(image)) {
            pg.start();
            int n = 0;
            for (Run run : runs) {
                String db = "env_" + n++;
                try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                     Statement st = admin.createStatement()) {
                    st.execute("create database " + db);
                }
                String url = pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
                try {
                    migrate(root, run, url, pg.getUsername(), pg.getPassword(), out);
                } catch (org.flywaydb.core.api.FlywayException e) {   // one environment's migrations fail: the others still count
                    problems.add((run.env() == null ? "" : run.env() + ": ") + firstLine(e)
                            + (lastDefaulted.isEmpty() ? "" : " (placeholders without a value, run with their own name: "
                            + String.join(", ", lastDefaulted) + ")"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("flyway: " + e.getMessage(), e);
        }
    }

    private static final Pattern MISSING = Pattern.compile("(schema|role) \"([^\"]+)\" does not exist");

    /**
     * #192: migrations that expect a schema or a role made outside Flyway (by a DBA, an init script, another service):
     * the missing one is created, empty, and the migrations run again (a failed one rolled back: PostgreSQL's DDL is
     * transactional). Said on stderr; what the migrations create still counts.
     */
    private static void migrateCreatingWhatIsMissing(org.flywaydb.core.api.configuration.FluentConfiguration flyway, String url,
                                                     String user, String password, Run run) throws SQLException {
        Set<String> created = new LinkedHashSet<>();
        while (true) {
            try {
                flyway.load().migrate();
                return;
            } catch (org.flywaydb.core.api.FlywayException e) {
                Matcher m = null;
                for (Throwable t = e; t != null && m == null; t = t.getCause()) {
                    Matcher mm = MISSING.matcher(String.valueOf(t.getMessage()));
                    if (mm.find()) {
                        m = mm;
                    }
                }
                if (m == null || created.size() >= 20 || !created.add(m.group(1) + " " + m.group(2))) {
                    throw e;
                }
                String name = m.group(2).replace("\"", "\"\"");
                try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
                    st.execute(m.group(1).equals("schema") ? "create schema if not exists \"" + name + "\"" : "create role \"" + name + "\"");
                }
                System.err.println("flyway" + (run.env() == null ? "" : " " + run.env()) + ": the migrations expect " + m.group(1) + " "
                        + m.group(2) + " to exist (made outside Flyway): created it empty and ran them again");
            }
        }
    }

    private void migrate(Path root, Run run, String url, String user, String password, FactSink out) throws SQLException {
        Map<String, String> config = run.env() == null ? SpringProperties.read(root) : SpringProperties.forProfile(root, run.env());
        List<Path> files = migrationFiles(run.folders());
        Map<String, String> placeholders = new TreeMap<>();
        Set<String> defaulted = new LinkedHashSet<>();
        lastDefaulted = defaulted;
        for (Path f : files) {
            Matcher m = PLACEHOLDER.matcher(read(f));
            while (m.find()) {
                String name = m.group(1);
                String value = config.get("spring.flyway.placeholders." + name);
                if (value == null) {
                    value = name;
                    defaulted.add(name);
                }
                placeholders.put(name, value);
            }
        }
        var flyway = Flyway.configure().dataSource(url, user, password)
                .locations(run.folders().stream().map(p -> "filesystem:" + p).toArray(String[]::new))
                .placeholders(placeholders).cleanDisabled(true).failOnMissingLocations(false);
        String schemas = config.get("spring.flyway.schemas");
        if (schemas != null && !schemas.isBlank()) {
            flyway.schemas(schemas.split("\\s*,\\s*"));
        }
        String defaultSchema = config.get("spring.flyway.default-schema");
        if (defaultSchema != null && !defaultSchema.isBlank()) {
            flyway.defaultSchema(defaultSchema);
        }
        if (run.schema() != null) {   // #179: a run the code makes names its own schema (or a stand-in, per tenant)
            flyway.defaultSchema(run.schema()).schemas(run.schema());
        }
        // a placeholder used as a schema ("${schema}.jobs") names a schema the migrations may expect to exist
        Set<String> schemaNames = new LinkedHashSet<>();
        for (Path f : files) {
            Matcher m = SCHEMA_PLACEHOLDER.matcher(read(f));
            while (m.find()) {
                schemaNames.add(placeholders.get(m.group(1)));
            }
        }
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String schema : schemaNames) {
                if (schema != null && schema.matches("[A-Za-z_][\\w$]*")) {
                    st.execute("create schema if not exists \"" + schema + "\"");
                }
            }
        }
        migrateCreatingWhatIsMissing(flyway, url, user, password, run);

        Map<String, Evidence> createdIn = createdIn(root, files, placeholders);
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            Map<String, List<String>> columns = columns(c);
            for (var t : columns.entrySet()) {
                String table = t.getKey();
                if (table.endsWith(".flyway_schema_history")) {
                    continue;
                }
                Map<String, Object> attrs = new TreeMap<>();
                attrs.put("columns", t.getValue());
                if (!defaulted.isEmpty()) {
                    attrs.put("placeholdersWithoutValue", List.copyOf(defaulted));
                }
                if (run.origin() != null) {
                    attrs.put("migratedBy", run.origin());   // the service's own code runs these migrations
                    if (run.perTenant() && table.startsWith(run.schema() + ".")) {
                        attrs.put("perTenant", true);   // the schema is each tenant's: this one is a stand-in
                    }
                }
                Evidence at = createdIn.getOrDefault(table, createdIn.getOrDefault(table.substring(table.indexOf('.') + 1),
                        Evidence.at(name(root, run.folders().getLast()), null)));
                Node n = new Node("table:" + table, "table", run.env(), attrs, at);
                out.node(n);
                out.edge(Edge.extracted("service:" + service, "has", n.id(), null, at).inEnv(run.env()));
                if (rowTables.contains(table) || rowTables.contains(table.substring(table.indexOf('.') + 1))) {
                    rows(c, table, run.env(), at, out);
                }
            }
        }
    }

    /** schema.table → its columns ({@code name type}), every table outside the system schemas. */
    private static Map<String, List<String>> columns(Connection c) throws SQLException {
        Map<String, List<String>> out = new TreeMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select c.table_schema, c.table_name, c.column_name, c.data_type "
                     + "from information_schema.columns c join information_schema.tables t "
                     + "on t.table_schema = c.table_schema and t.table_name = c.table_name and t.table_type = 'BASE TABLE' "
                     + "order by c.table_schema, c.table_name, c.ordinal_position")) {
            while (rs.next()) {
                if (SYSTEM_SCHEMAS.contains(rs.getString(1))) {
                    continue;
                }
                out.computeIfAbsent(rs.getString(1) + "." + rs.getString(2), k -> new ArrayList<>())
                        .add(rs.getString(3) + " " + rs.getString(4));
            }
        }
        return out;
    }

    /** The rows of a table as {@code row:<table>:<key>} nodes: the primary key's values, else the row's position. */
    private static void rows(Connection c, String table, String env, Evidence at, FactSink out) throws SQLException {
        String[] st2 = table.split("\\.", 2);
        List<String> key = new ArrayList<>();
        try (var ps = c.prepareStatement("select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid "
                + "and a.attnum = any(i.indkey) where i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass "
                + "and i.indisprimary order by a.attnum")) {
            ps.setString(1, st2[0]);
            ps.setString(2, st2[1]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    key.add(rs.getString(1));
                }
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select * from \"" + st2[0] + "\".\"" + st2[1] + "\""
                     + (key.isEmpty() ? "" : " order by " + String.join(", ", key.stream().map(k -> "\"" + k + "\"").toList())))) {
            ResultSetMetaData md = rs.getMetaData();
            int pos = 0;
            while (rs.next()) {
                pos++;
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    values.put(md.getColumnName(i), v == null || v instanceof Number || v instanceof Boolean ? v
                            : dev.logast.facts.Secrets.clean(md.getColumnName(i), v.toString()));   // #183: a row may hold a credential
                }
                List<String> keyValues = new ArrayList<>();
                for (String k : key) {
                    keyValues.add(String.valueOf(values.get(k)));
                }
                String id = "row:" + table + ":" + (key.isEmpty() ? "#" + pos : String.join(",", keyValues));
                out.node(new Node(id, "row", env, values, at));
                out.edge(Edge.extracted("table:" + table, "has", id, null, at).inEnv(env));
            }
        }
    }

    /** The table each migration file creates (by its create statement), with placeholders replaced: the evidence. */
    private Map<String, Evidence> createdIn(Path root, List<Path> files, Map<String, String> placeholders) {
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (Path f : files) {
            String text = read(f);
            Matcher m = CREATE_TABLE.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                for (var p : placeholders.entrySet()) {
                    name = name.replace("${" + p.getKey() + "}", p.getValue());
                }
                name = DataAccess.tableName(name.replace("\"", ""));
                int line = (int) text.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
                out.putIfAbsent(name, Evidence.at(name(root, f), line));
            }
        }
        return out;
    }

    private static List<Path> migrationFiles(List<Path> folders) {
        List<Path> out = new ArrayList<>();
        for (Path d : folders) {
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(f -> Files.isRegularFile(f) && MIGRATION.matcher(f.getFileName().toString()).matches()).sorted().forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A file's evidence name: {@code <jar>!/<entry>} for a library's migration, else its path in the repo. */
    private String name(Path root, Path p) {
        String jar = cp.name(p);
        return jar != null ? jar : rel(root, p);
    }

    private static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
