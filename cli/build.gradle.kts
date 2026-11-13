plugins { application }

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    runtimeOnly(project(":test-capture"))   // shipped in lib/; `logast verify` adds it to a service's test runtime
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("logast-version.properties") { expand("version" to v) }
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

// test-capture is a JUnit listener for *services'* tests (it needs logback); keep it out of logast's own tests
configurations.testRuntimeClasspath { exclude(group = rootProject.group.toString(), module = "test-capture") }
