package dev.logast.fleet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #147: the files {@code logast fleet init} adds to a fleet kept in a git repo: the member list by URL, a README for the
 * owner and for service devs, the workflow that refreshes and publishes the report, and the .gitignore for what is
 * generated. Existing files are never overwritten.
 */
public final class FleetScaffold {
    public static final String WORKFLOW = ".github/workflows/logast-fleet.yml";
    public static final String CLASSPATH_BUILD = "classpath/build.gradle.kts";

    /**
     * The libraries the survey resolves the members' types against (#147): built by the fleet repo's own Gradle, so the
     * workflow never runs a member's build script and still sees RestClient, Feign, Kafka... calls.
     */
    static final String CLASSPATH_GRADLE = """
            // The libraries the fleet's services use: the workflow resolves the members' code against them, so it never has
            // to run a member's own build (`gradle -p classpath writeClasspath` writes ../.cache/classpath.txt).
            // Add your company's libraries (and the repository they come from) as the survey needs them.
            plugins {
                `java-library`
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))
                implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.0.0"))
                listOf(
                    "org.springframework.boot:spring-boot-starter-web",
                    "org.springframework.boot:spring-boot-starter-webflux",
                    "org.springframework.boot:spring-boot-starter-actuator",
                    "org.springframework.boot:spring-boot-starter-data-jpa",
                    "org.springframework.boot:spring-boot-starter-amqp",
                    "org.springframework.kafka:spring-kafka",
                    "org.springframework.cloud:spring-cloud-starter-openfeign",
                    "io.micrometer:micrometer-tracing-bridge-otel",
                    "org.projectlombok:lombok",
                ).forEach { implementation(it) }
            }

            tasks.register("writeClasspath") {
                val classpath = configurations.runtimeClasspath
                val out = layout.projectDirectory.file("../.cache/classpath.txt").asFile
                doLast {
                    out.parentFile.mkdirs()
                    out.writeText(classpath.get().files.joinToString("\n"))
                }
            }
            """;

    public static final String LOGAST_REPO = "https://github.com/magyar-attila-317/logast.git";

    /** #182: what people know that no file proves; logast mcp applies it to the facts (docs/mcp.md). */
    static final String KNOWLEDGE = """
            # What people know that no repo proves, applied to the facts when logast mcp loads kb/ (docs/mcp.md):
            # the glossary, abbreviations, other names of services, and decisions on links logast inferred.
            # Uncomment and fill in; logast never rewrites this file (decisions taken on the review page go to
            # knowledge.decisions.yml, the LLM's proposals to knowledge.proposed.yml).

            # glossary:
            #   order:
            #     definition: A customer's request to buy, from checkout to delivery.
            #     source: https://wiki.example.com/glossary#order
            #     aliases: [purchase]
            # abbreviations:
            #   ord: order
            # services:
            #   orders: [order-service]
            # decisions:
            #   - link: "service:billing -deployed-as-> deployment:billing-worker"
            #     decision: approve
            #     by: jane
            """;

    static final String MAPPINGS = """
            # Mappings

            Company-specific facts from the exported ones, without code: each `*.yaml` here is applied by
            `logast fleet . --kb` to the facts under `kb/`, into `kb/mapped/<file>.jsonl` (docs/facts.md, "Company-specific
            facts"). For example, a scheduler's job rows (exported with `--flyway --rows`) as jobs with their schedule.
            """;

    private FleetScaffold() {
    }

    /** relative path → content, in the order they are reported. */
    public static Map<String, String> files(String name, String logastRef) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(FleetRepos.FILE, FleetRepos.template(name));
        out.put("README.md", readme(name));
        out.put(WORKFLOW, workflow(logastRef));
        out.put(CLASSPATH_BUILD, CLASSPATH_GRADLE);
        out.put("classpath/settings.gradle.kts", "rootProject.name = \"logast-classpath\"\n");
        out.put("kb/knowledge.yml", KNOWLEDGE);   // #182: the people's part of the fleet's knowledge base
        out.put("kb/mappings/README.md", MAPPINGS);
        out.put(".gitignore", """
                # generated by logast: the members' clones, their snapshots and scan output, the report
                .cache/
                services/
                report/
                # the member list with this machine's paths (repos.yaml is the one to edit)
                members.yaml
                """);
        return out;
    }

    /** Writes the files that don't exist yet; returns path → true when written, false when an existing file was kept. */
    public static Map<String, Boolean> write(Path dir, String name, String logastRef) {
        Map<String, Boolean> done = new LinkedHashMap<>();
        try {
            for (var e : files(name, logastRef).entrySet()) {
                Path f = dir.resolve(e.getKey());
                if (Files.exists(f)) {
                    done.put(e.getKey(), false);
                    continue;
                }
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue());
                done.put(e.getKey(), true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return done;
    }

    static String readme(String name) {
        return """
                # %1$s: the logast fleet

                The services of this fleet, their logging / tracing / metrics findings, how they call each other, and the
                settings they share. [logast](https://github.com/magyar-attila-317/logast) keeps it current; the report is
                `report/fleet.html` (and the published page, if enabled below).

                ## For the fleet owner

                1. **List the repos** in `repos.yaml` (URLs; private repos need access, see the workflow below).
                2. **Look at them**, read-only: `logast fleet . --refresh` clones each repo (shallow) into `.cache/repos/`,
                   scans it and writes `report/fleet.html`. Nothing is written into the repos.
                3. **Decide the company settings** in `company.yaml`: its suggestions part shows what the repos agree on
                   ("3 of 3 members use it": uncomment the line). Every service that names this fleet follows it.
                4. **Commit and push.** The workflow refreshes the report on every push to main and on weekday mornings.

                ## The knowledge base (`kb/`)

                Facts about every repo of `repos.yaml`, for asking an LLM across all of them ("who writes table x",
                "what runs on Monday", "what happens after this call"): `logast fleet . --refresh --kb` exports each repo,
                whatever its layout (Gradle builds, helmfile folders, Terragrunt units, workflows), into `kb/<repo>/`, and
                the workflow does it every run and commits `kb/`. Yours to edit: `kb/knowledge.yml` (glossary, other names
                of services, decisions) and `kb/mappings/` (company-specific facts). To ask, in a clone:

                ```bash
                git pull
                logast mcp config --kb "$PWD/kb"     # paste the printed entry into your MCP client
                ```

                The facts name endpoints, tables, hosts and file paths: the clone is as private as this repo.

                ### The workflow (`.github/workflows/logast-fleet.yml`)

                - It builds logast at a fixed commit (`LOGAST_REF`), clones the repos of `repos.yaml`, runs
                  `logast fleet . --refresh --kb --no-builds --classpath-file .cache/classpath.txt`, commits `kb/` when it changed, shows the overview on the run's summary page and keeps `report/`
                  as the run's artifact (Actions → the run → Artifacts). Both are private like this repo.
                - `--no-builds`: the repos' own build scripts never run in this job (they would run next to a token that
                  reads every repo). The members' code is resolved against the libraries of `classpath/build.gradle.kts`
                  instead (common Spring ones; add your company's): good for a survey, a little less precise than a scan
                  in the repo itself.
                - **Private repos**: add a secret `LOGAST_REPOS_TOKEN` (Settings → Secrets → Actions) with read access to
                  them: a fine-grained token with *Contents: read* on those repos, or a GitHub App's token. Without it only
                  public repos can be cloned.
                - **Publishing the page** (GitHub Pages) is off until you turn it on: set the repository variable
                  `LOGAST_PAGES` to `true` (Settings → Secrets and variables → Actions → Variables) and choose
                  Settings → Pages → Source: *GitHub Actions*.
                  **Check who can see it first**: on GitHub Free, Pro and Team, a Pages site of a private repo is public on
                  the internet; only GitHub Enterprise Cloud can restrict it to the organisation. The report shows service
                  names, endpoints, hosts and file paths.

                ## For service developers

                In your service's folder:

                ```bash
                logast apply .     # sets itself up, changes the code; the result compiles
                git diff           # review, then commit
                ```

                To follow this fleet's company settings (log format, key style, the shared vocabulary), register the fleet
                once on your machine and join it:

                ```bash
                git clone <this repo's URL> ~/logast-fleets/%1$s
                logast fleet create %1$s --dir ~/logast-fleets/%1$s    # registers the clone on this machine
                cd <your service> && logast fleet join %1$s            # writes `fleet: %1$s` into logast.yaml
                ```

                `git pull` in the clone brings the newest company settings; your next `logast apply .` follows them. The
                fleet's name in `logast.yaml` is the same on every machine, so the line can be committed.
                """.formatted(name);
    }

    static String workflow(String logastRef) {
        return """
                # Refreshes the fleet report: clones the repos of repos.yaml, scans them (read-only), keeps report/ as an
                # artifact (and the overview on the run's summary) and, when the repository variable LOGAST_PAGES is 'true', publishes
                # it to GitHub Pages. The members' build scripts never run here (--no-builds).
                # Written by `logast fleet init`; edit freely. LOGAST_REF pins the logast version the report is made with.
                name: logast fleet

                on:
                  push:
                    branches: [main, master]
                  schedule:
                    - cron: "0 5 * * 1-5"
                  workflow_dispatch:

                permissions:
                  contents: write   # commits kb/ (the knowledge base) when it changed
                  pages: write
                  id-token: write

                concurrency:
                  group: logast-fleet
                  cancel-in-progress: true

                env:
                  LOGAST_REF: %s

                jobs:
                  report:
                    runs-on: ubuntu-latest
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: |
                            17
                            21
                            25
                      - name: Install logast
                        run: |
                          git clone --quiet %s "$RUNNER_TEMP/logast"
                          git -C "$RUNNER_TEMP/logast" checkout --quiet "$LOGAST_REF"
                          (cd "$RUNNER_TEMP/logast" && ./gradlew --quiet :cli:installDist)
                          echo "$RUNNER_TEMP/logast/cli/build/install/logast/bin" >> "$GITHUB_PATH"
                      - name: Access to the member repos
                        env:
                          TOKEN: ${{ secrets.LOGAST_REPOS_TOKEN }}
                        if: env.TOKEN != ''
                        run: |
                          git config --global url."https://x-access-token:${TOKEN}@github.com/".insteadOf "https://github.com/"
                          git config --global --add url."https://x-access-token:${TOKEN}@github.com/".insteadOf "git@github.com:"
                      - uses: gradle/actions/setup-gradle@v4
                      - name: The libraries to resolve the members' code against (classpath/build.gradle.kts)
                        run: gradle --quiet -p classpath writeClasspath
                      - name: Refresh the fleet
                        # --no-builds: the members' build scripts never run here, next to a token that reads every repo;
                        # the scan is source-only (survey quality). The overview goes to the run's summary page.
                        run: |
                          set -o pipefail
                          { echo '```'; logast fleet . --refresh --kb --no-builds --classpath-file .cache/classpath.txt; echo '```'; } | tee -a "$GITHUB_STEP_SUMMARY"
                      - name: Commit the knowledge base
                        # kb/ is the fleet's knowledge base for logast mcp: a git pull brings it to everyone's client.
                        # Pushed with this job's own token (the members' token above only reads).
                        env:
                          GITHUB_TOKEN: ${{ github.token }}
                        run: |
                          git add kb
                          if git diff --cached --quiet; then echo "kb/ unchanged"; exit 0; fi
                          git -c user.name="logast" -c user.email="logast@users.noreply.github.com" commit --quiet -m "kb: refresh"
                          git push --quiet "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "HEAD:${GITHUB_REF_NAME}"
                      - name: Keep the report
                        uses: actions/upload-artifact@v4
                        with:
                          name: logast-fleet-report
                          path: report/
                      - name: Prepare the page
                        if: vars.LOGAST_PAGES == 'true'
                        run: cp report/fleet.html report/index.html
                      - if: vars.LOGAST_PAGES == 'true'
                        uses: actions/upload-pages-artifact@v3
                        with:
                          path: report/

                  pages:
                    # only when the repository variable LOGAST_PAGES is 'true': read the README on who can see the page
                    if: vars.LOGAST_PAGES == 'true'
                    needs: report
                    runs-on: ubuntu-latest
                    environment:
                      name: github-pages
                      url: ${{ steps.deploy.outputs.page_url }}
                    steps:
                      - uses: actions/configure-pages@v5
                      - id: deploy
                        uses: actions/deploy-pages@v4
                """.formatted(logastRef, LOGAST_REPO);
    }
}
