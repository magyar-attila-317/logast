plugins { application }

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    runtimeOnly(project(":test-capture"))   // shipped in lib/; `logast verify` adds it to a service's test runtime
}

// `logast --version` names the commit it was built from, so an install that did not update is visible
val gitCommit = providers.exec {
    commandLine("git", "describe", "--always", "--dirty", "--abbrev=7")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.processResources {
    val v = project.version.toString()
    val c = gitCommit.orElse("unknown")
    inputs.property("version", v)
    inputs.property("commit", c)
    filesMatching("logast-version.properties") { expand("version" to v, "commit" to c.get()) }
}

// release zip: bin/, lib/, plus docs, examples and the Claude Code skill
distributions {
    main {
        contents {
            from(rootProject.file("README.md"))
            from(rootProject.file("docs")) { into("docs") }
            from(rootProject.file("examples")) { into("examples") }
            from(rootProject.file("docs/llm-workflow.md")) { into("claude-skill/logast"); rename { "SKILL.md" } }
        }
    }
}

application {
    mainClass = "dev.logast.cli.Main"
    applicationName = "logast"
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=50")
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

// e2e golden test: `./gradlew :cli:test -Pe2e.update` rewrites e2e/expected after an intended behaviour change
tasks.test {
    inputs.files(e2eLombok)
    systemProperty("logast.e2e.lombok", e2eLombok.asPath)
    useJUnitPlatform()
    dependsOn(":fixtures:sample-app:writeClasspath")
    systemProperty("logast.e2e.before", rootProject.file("e2e/before").absolutePath)
    systemProperty("logast.e2e.expected", rootProject.file("e2e/expected").absolutePath)
    systemProperty("logast.e2e.classpath", project(":fixtures:sample-app").layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
    systemProperty("logast.e2e.update", providers.gradleProperty("e2e.update").isPresent.toString())
    inputs.dir(rootProject.file("e2e"))
    maxHeapSize = "2g"
}

// The same golden e2e test on the oldest Java logast supports: javac's type attribution differs between versions
// (JDK 21 drops the signature of a call that passes a value of an unresolved type), and the result must not.
val e2eJdk21 by tasks.registering(Test::class) {
    description = "E2eGoldenTest on Java 21: the same e2e/expected files"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    useJUnitPlatform()
    filter { includeTestsMatching("dev.logast.cli.E2eGoldenTest") }
    dependsOn(":fixtures:sample-app:writeClasspath")
    inputs.files(e2eLombok)
    inputs.dir(rootProject.file("e2e"))
    systemProperty("logast.e2e.lombok", e2eLombok.asPath)
    systemProperty("logast.e2e.before", rootProject.file("e2e/before").absolutePath)
    systemProperty("logast.e2e.expected", rootProject.file("e2e/expected").absolutePath)
    systemProperty("logast.e2e.classpath", project(":fixtures:sample-app").layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
    maxHeapSize = "2g"
}
tasks.check { dependsOn(e2eJdk21) }

// test-capture is a JUnit listener for *services'* tests (it needs logback); keep it out of logast's own tests
configurations.testRuntimeClasspath { exclude(group = rootProject.group.toString(), module = "test-capture") }
