package dev.logast.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #256: the support library is the team's once it runs in its services: {@code logast support publish} puts the one this
 * logast ships ({@code lib/support/}: logging-support and test-capture, jars, sources, POMs) into the team's own Maven
 * repository, under the version and group it chooses, with what a Maven repository takes (HTTP PUT, checksums,
 * maven-metadata.xml). No checkout, no Gradle.
 */
@Command(name = "support", hidden = true, description = "The support library logast ships, for the team's own repository (docs/platform.md)",
        subcommands = {SupportCmd.Publish.class})
final class SupportCmd {
    static final List<String> ARTIFACTS = List.of("logging-support", "test-capture");

    @Command(name = "publish", mixinStandardHelpOptions = true,
            description = "Publish the support library and the test capture to a Maven repository (dependency mode)")
    static final class Publish implements Callable<Integer> {
        @Option(names = "--repo", required = true, description = "github.com/<owner>/<repo> (GitHub Packages), or a Maven repository URL")
        String repo;
        @Option(names = "--version", description = "The version to publish under (default: the one this logast ships)")
        String version;
        @Option(names = "--group", defaultValue = "dev.logast", description = "The group to publish under (default: ${DEFAULT-VALUE})")
        String group;
        @Option(names = "--credentials", description = "user:token (default: REPO_USER/REPO_PASSWORD, else GITHUB_ACTOR/GITHUB_TOKEN)")
        String credentials;
        @Option(names = "--from", hidden = true, description = "The folder with the shipped artifacts (default: this logast's lib/support)")
        Path from;

        @Override
        public Integer call() throws InterruptedException {
            try {
                return publish();
            } catch (IOException e) {   // one line, not a stack trace: what failed and what to do
                System.err.println("support: " + e.getMessage());
                return 1;
            }
        }

        private Integer publish() throws IOException, InterruptedException {
            Path dir = from != null ? from : shipped();
            if (dir == null || !Files.isDirectory(dir)) {
                System.err.println("support: no lib/support next to this logast (" + dir + "): a release zip or ./gradlew :cli:installDist has it");
                return 2;
            }
            String shipped = shippedVersion(dir);
            if (shipped == null) {
                System.err.println("support: " + dir + " holds no logging-support-<version>.jar");
                return 2;
            }
            String v = version != null ? version : shipped;
            String url = repositoryUrl(repo);
            Repository target = Repository.of(url, auth(credentials));
            for (String artifact : ARTIFACTS) {
                String base = group.replace('.', '/') + "/" + artifact + "/" + v + "/" + artifact + "-" + v;
                Map<String, byte[]> files = new java.util.LinkedHashMap<>();
                files.put(base + ".jar", Files.readAllBytes(dir.resolve(artifact + "-" + shipped + ".jar")));
                Path sources = dir.resolve(artifact + "-" + shipped + "-sources.jar");
                if (Files.exists(sources)) {
                    files.put(base + "-sources.jar", Files.readAllBytes(sources));
                }
                files.put(base + ".pom", pom(Files.readString(dir.resolve(artifact + "-" + shipped + ".pom")), group, v)
                        .getBytes(StandardCharsets.UTF_8));
                for (var f : files.entrySet()) {
                    target.put(f.getKey(), f.getValue());
                    target.put(f.getKey() + ".sha1", hash("SHA-1", f.getValue()).getBytes(StandardCharsets.US_ASCII));
                    target.put(f.getKey() + ".md5", hash("MD5", f.getValue()).getBytes(StandardCharsets.US_ASCII));
                }
                String meta = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
                byte[] metadata = metadata(group, artifact, v, target.get(meta)).getBytes(StandardCharsets.UTF_8);
                try {
                    target.put(meta, metadata);
                    target.put(meta + ".sha1", hash("SHA-1", metadata).getBytes(StandardCharsets.US_ASCII));
                    target.put(meta + ".md5", hash("MD5", metadata).getBytes(StandardCharsets.US_ASCII));
                } catch (IOException e) {   // the versions still resolve by name; only listings miss this one
                    System.err.println("support: " + artifact + ": maven-metadata.xml not written (" + e.getMessage() + ")");
                }
                System.out.println("published " + group + ":" + artifact + ":" + v + " to " + url);
            }
            System.out.println();
            System.out.println("In the company policy (or each service's logast.yaml):");
            System.out.println("  support:");
            System.out.println("    mode: dependency");
            System.out.println("    coordinates: " + group + ":logging-support:" + v);
            System.out.println("    repository: " + url);
            return 0;
        }
    }

    /** {@code github.com/<owner>/<repo>} (with or without https://) is GitHub Packages; anything else a repository URL. */
    static String repositoryUrl(String repo) {
        Matcher gh = Pattern.compile("^(?:https?://)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$").matcher(repo.strip());
        if (gh.matches()) {
            return "https://maven.pkg.github.com/" + gh.group(1) + "/" + gh.group(2);
        }
        return repo.strip().replaceAll("/+$", "");
    }

    /** Basic auth from {@code user:token}, else the environment; null without any. */
    private static String auth(String credentials) {
        String user;
        String secret;
        if (credentials != null) {
            int colon = credentials.indexOf(':');
            user = colon < 0 ? "" : credentials.substring(0, colon);
            secret = colon < 0 ? credentials : credentials.substring(colon + 1);
        } else if (System.getenv("REPO_PASSWORD") != null) {
            user = System.getenv().getOrDefault("REPO_USER", "");
            secret = System.getenv("REPO_PASSWORD");
        } else if (System.getenv("GITHUB_TOKEN") != null) {
            user = System.getenv().getOrDefault("GITHUB_ACTOR", "token");
            secret = System.getenv("GITHUB_TOKEN");
        } else {
            return null;
        }
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    /** This logast's lib/support: next to the jar this class came from (lib/). */
    private static Path shipped() {
        try {
            Path code = Path.of(SupportCmd.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return code.getParent() == null ? null : code.getParent().resolve("support");
        } catch (Exception e) {
            return null;
        }
    }

    private static String shippedVersion(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).map(n -> n.matches("logging-support-(.+)\\.jar") && !n.endsWith("-sources.jar")
                    ? n.substring("logging-support-".length(), n.length() - ".jar".length()) : null).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(null);
        }
    }

    /**
     * The shipped POM under the chosen coordinates: the project's own groupId and version (the first of each, before
     * any dependency's), without Gradle's "published with module metadata" marker (no .module file goes with it).
     */
    static String pom(String pom, String group, String version) {
        String out = pom.replaceAll("(?m)^\\s*<!--[^\\n]*(Gradle metadata|richer model|is to indicate|consumer|do_not_remove|which should be used|that they should)[^\\n]*-->\\R", "");
        out = out.replaceFirst("<groupId>[^<]*</groupId>", "<groupId>" + Matcher.quoteReplacement(group) + "</groupId>");
        out = out.replaceFirst("(</artifactId>\\s*)<version>[^<]*</version>", "$1<version>" + Matcher.quoteReplacement(version) + "</version>");
        return out;
    }

    /** maven-metadata.xml with {@code version} added to what the repository had (null: nothing yet). */
    static String metadata(String group, String artifact, String version, byte[] existing) {
        TreeSet<String> versions = new TreeSet<>(SupportCmd::compareVersions);
        if (existing != null) {
            Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(new String(existing, StandardCharsets.UTF_8));
            while (m.find()) {
                versions.add(m.group(1).strip());
            }
        }
        versions.add(version);
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n");
        sb.append("  <groupId>").append(group).append("</groupId>\n  <artifactId>").append(artifact).append("</artifactId>\n");
        sb.append("  <versioning>\n    <latest>").append(versions.last()).append("</latest>\n    <release>").append(versions.last())
                .append("</release>\n    <versions>\n");
        versions.forEach(x -> sb.append("      <version>").append(x).append("</version>\n"));
        sb.append("    </versions>\n    <lastUpdated>")
                .append(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .append("</lastUpdated>\n  </versioning>\n</metadata>\n");
        return sb.toString();
    }

    /** 0.10.0 after 0.9.1: numbers compared as numbers, the rest as text. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "";
            String q = i < y.length ? y[i] : "";
            int c = p.matches("\\d+") && q.matches("\\d+") ? Long.compare(Long.parseLong(p), Long.parseLong(q)) : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static String hash(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Maven repository: a folder ({@code file://}) or HTTP(S) with PUT and GET. */
    interface Repository {
        void put(String path, byte[] content) throws IOException, InterruptedException;

        /** The file's content, or null when it is not there. */
        byte[] get(String path) throws IOException, InterruptedException;

        static Repository of(String url, String auth) {
            if (url.startsWith("file:")) {
                Path root = Path.of(URI.create(url));
                return new Repository() {
                    @Override
                    public void put(String path, byte[] content) throws IOException {
                        Path p = root.resolve(path);
                        Files.createDirectories(p.getParent());
                        Files.write(p, content);
                    }

                    @Override
                    public byte[] get(String path) throws IOException {
                        Path p = root.resolve(path);
                        return Files.exists(p) ? Files.readAllBytes(p) : null;
                    }
                };
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
            return new Repository() {
                private HttpRequest.Builder request(String path) {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/" + path)).timeout(Duration.ofMinutes(2));
                    return auth == null ? b : b.header("Authorization", auth);
                }

                @Override
                public void put(String path, byte[] content) throws IOException, InterruptedException {
                    HttpResponse<String> r = http.send(request(path).PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() / 100 != 2) {
                        boolean github = url.startsWith("https://maven.pkg.github.com/");
                        String why = r.statusCode() == 401 || r.statusCode() == 403
                                ? github ? " (GitHub Packages: a token of a user who may write to that repository, with the "
                                        + "write:packages scope (and repo for a private one); `gh auth token` has it only after "
                                        + "`gh auth refresh -s write:packages`)"
                                        : " (credentials: --credentials user:token, or REPO_USER/REPO_PASSWORD)"
                                : r.statusCode() == 409 ? " (this version is there already: publish another --version)" : "";
                        throw new IOException("PUT " + path + ": HTTP " + r.statusCode() + why);
                    }
                }

                @Override
                public byte[] get(String path) throws IOException, InterruptedException {
                    HttpResponse<byte[]> r = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    return r.statusCode() / 100 == 2 ? r.body() : null;
                }
            };
        }
    }

    private SupportCmd() {
    }
}
