plugins {
    application
    `maven-publish`   // the Gradle plugin depends on it (#115)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kb"))   // #174: logast mcp
    implementation(libs.picocli)
    runtimeOnly(project(":test-capture"))   // shipped in lib/; `logast verify` adds it to a service's test runtime
}

// `logast --version` names the commit it was built from, so an install that did not update is visible
val gitCommit = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty", "--abbrev=7")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.processResources {
    val v = project.version.toString()
    val c = gitCommit.orElse("unknown")
    inputs.property("version", v)
    inputs.property("commit", c)
    filesMatching("logast-version.properties") { expand("version" to v, "commit" to c.get()) }
}

// #256: the support library as it is published, for `logast support publish`
evaluationDependsOn(":logging-support")

// release zip: bin/, lib/, plus docs, examples and the Claude Code skill
distributions {
    main {
        contents {
            for (lib in listOf("logging-support")) {   // test-capture: logast verify takes it from lib/
                val p = project(":$lib")
                from(p.tasks.named("jar")) { into("lib/support") }
                from(p.tasks.named("sourcesJar")) { into("lib/support") }
                from(p.tasks.named("generatePomFileForLogastPublication")) {
                    into("lib/support")
                    rename { "$lib-${project.version}.pom" }
                }
            }
            from(rootProject.file("README.md"))
            // `logast update` runs it to replace this install with another release (a download, no checkout)
            from(rootProject.file("install.sh")) { filePermissions { unix("rwxr-xr-x") } }
            from(rootProject.file("docs")) { into("docs") }
            from(rootProject.file("examples")) { into("examples") }
            from(rootProject.file("docs/llm-workflow.md")) { into("claude-skill/logast"); rename { "SKILL.md" } }
            // the init script install.sh copies to ~/.gradle/init.d/: dev.logast in every Java build, this version (#122)
            from(rootProject.file("modules/gradle-plugin/src/init/logast.gradle")) {
                into("gradle")
                val v = project.version.toString()
                filter { line: String -> line.replace("@LOGAST_VERSION@", v) }
            }
        }
    }
}

application {
    mainClass = "dev.logast.cli.Main"
    applicationName = "logast"
    // #276: a third of the machine, so the JVM reaches its own limit (and says so) before the system's OOM killer ends it
    // silently next to the build's other JVMs; LOGAST_OPTS=-Xmx… sets it
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=35")
}

// Java 24+ warns on every run that OpenRewrite's Lombok support uses sun.misc.Unsafe. The flag that silences it does
// not exist on Java 21 (the JVM would refuse to start), so the Unix start script adds it only on 24 and later.
tasks.startScripts {
    doLast {
        val script = unixScript
        script.writeText(script.readText().replace(
            "# Collect all arguments for the java command:\n",
            """
            |case "${'$'}("${'$'}JAVACMD" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)" in
            |    2[4-9]|[3-9][0-9]) DEFAULT_JVM_OPTS="${'$'}DEFAULT_JVM_OPTS --sun-misc-unsafe-memory-access=allow" ;;
            |esac
            |
            |# Collect all arguments for the java command:
            |""".trimMargin()))
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

// Lombok for the e2e project only (real services use it; the fixture stays without)
val e2eLombok by configurations.creating
dependencies {
    e2eLombok(platform(libs.spring.boot.bom))
    e2eLombok("org.projectlombok:lombok")
}

// e2e golden test (#73): slow (each scenario copies, applies, rescans and compiles the sample service), so it runs in
// its own tasks. Locally: `./gradlew :cli:e2e --tests "*Name*"` for the scenarios a change touches; the full matrix
// (`e2e` on JDK 25, `e2eJdk21`) runs on CI or with -Pe2e. `./gradlew :cli:e2e -Pe2e.update` rewrites e2e/expected
// after an intended behaviour change.
val e2eGolden = "dev.logast.cli.E2eGoldenTest"
// #110: the scenarios run in E2eShard0..7 (each an eighth of E2eGoldenTest's scenarios), one JVM per shard in parallel
val e2eShards = "dev.logast.cli.E2eShard*"

fun Test.e2eSetup() {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(e2eShards) }
    // scenarios run logast in-process (System.out is captured): parallel only as separate JVMs, up to 4, 2 GB each;
    // -Pe2e.forks=1 on a machine short on memory
    maxParallelForks = providers.gradleProperty("e2e.forks").map(String::toInt).orNull
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    dependsOn(":fixtures:sample-app:writeClasspath", ":logging-support:classes")
    // dependency mode: javac compiles the migrated service against the starter's classes (it is not on the classpath logast reads)
    systemProperty("logast.e2e.support", project(":logging-support").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath)
    inputs.files(e2eLombok)
    inputs.dir(rootProject.file("testing/e2e"))
    systemProperty("logast.e2e.lombok", e2eLombok.asPath)
    systemProperty("logast.e2e.before", rootProject.file("testing/e2e/before").absolutePath)
    systemProperty("logast.e2e.expected", rootProject.file("testing/e2e/expected").absolutePath)
    systemProperty("logast.e2e.classpath", project(":fixtures:sample-app").layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
    systemProperty("logast.e2e.update", providers.gradleProperty("e2e.update").isPresent.toString())
    maxHeapSize = "2g"
}

tasks.test {
    useJUnitPlatform()
    filter {
        excludeTestsMatching(e2eGolden)
        excludeTestsMatching(e2eShards)
        excludeTestsMatching(privateRun)
        excludeTestsMatching(corpusRun)
    }
}

// #9: public Spring Boot services (corpus/corpus.yaml) cloned at pinned commits and migrated; network and minutes per
// service, so its own task (on demand, locally or the corpus workflow in CI). -Pcorpus.only=petclinic,… runs a few.
val corpusRun = "dev.logast.cli.CorpusRun"
val corpus by tasks.registering(Test::class) {
    description = "Migrates the public services of corpus/corpus.yaml and checks the migration invariants (#9)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(corpusRun) }
    systemProperty("logast.corpus.file", rootProject.file("testing/corpus/corpus.yaml").absolutePath)
    providers.gradleProperty("corpus.only").orNull?.let { systemProperty("logast.corpus.only", it) }
    // #328: the first apply's decisions against testing/corpus/decisions/<service>.txt; -Pcorpus.updateDecisions rewrites them
    systemProperty("logast.corpus.decisions", rootProject.file("testing/corpus/decisions").absolutePath)
    systemProperty("logast.corpus.updateDecisions", providers.gradleProperty("corpus.updateDecisions").isPresent.toString())
    outputs.upToDateWhen { false }
    maxHeapSize = "4g"
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// #10: the owner's real services, outside git (~/.logast-private/<service>/{before,expected,classpath.txt}); nothing to
// run where the folder does not exist. -Pprivate.update records expected/ after a reviewed change.
val privateRun = "dev.logast.cli.PrivateRegressionRun"
val privateRegression by tasks.registering(Test::class) {
    description = "Migrates every service under ~/.logast-private and compares it with its recorded expected/ (#10)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter {
        includeTestsMatching(privateRun)
        isFailOnNoMatchingTests = false   // no ~/.logast-private: nothing to run
    }
    val dir = providers.gradleProperty("logast.private.dir").orNull
    dir?.let { systemProperty("logast.private.dir", it) }
    doFirst {
        val root = file(dir ?: (System.getProperty("user.home") + "/.logast-private"))
        if (!root.isDirectory) {
            logger.lifecycle("privateRegression: no $root, nothing to run (see issue #10 for the layout)")
        }
    }
    systemProperty("logast.private.update", providers.gradleProperty("private.update").isPresent.toString())
    outputs.upToDateWhen { false }   // the services change outside the build
    maxHeapSize = "4g"
}

val e2e by tasks.registering(Test::class) {
    description = "E2eGoldenTest on the build JDK (25): every file of the result against e2e/expected"
    e2eSetup()
}

// The same golden e2e test on the oldest Java logast supports: javac's type attribution differs between versions
// (JDK 21 drops the signature of a call that passes a value of an unresolved type), and the result must not.
val e2eJdk21 by tasks.registering(Test::class) {
    description = "E2eGoldenTest on Java 21: the same e2e/expected files"
    e2eSetup()
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    // #308: every apply round parses in full here, and incrementally in `e2e`: both must give the same snapshots
    systemProperty("logast.parse", "full")
}

if (providers.gradleProperty("e2e").isPresent || providers.environmentVariable("CI").isPresent) {
    tasks.check { dependsOn(e2e, e2eJdk21) }
}

// test-capture is a JUnit listener for *services'* tests (it needs logback); keep it out of logast's own tests
configurations.testRuntimeClasspath { exclude(group = rootProject.group.toString(), module = "test-capture") }
