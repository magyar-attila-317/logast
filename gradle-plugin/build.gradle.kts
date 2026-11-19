// The `dev.logast` Gradle plugin (#115): logastScan / logastBaseline / logastCheck in a service's own build, with the
// build's resolved compile classpath (no `logast classpath` step). It runs the logast CLI (this repo's :cli) in a
// Gradle worker process, so the scan gets its own heap and JVM and the build's daemon stays untouched.
plugins {
    `java-gradle-plugin`
    `maven-publish`
}

description = "Gradle plugin: logast scan and the CI baseline gate in a service's build"

dependencies {
    implementation(project(":cli")) {
        exclude(group = "dev.logast", module = "test-capture")   // only `logast verify` uses it, never the plugin
    }
    implementation(libs.picocli)
    implementation(libs.jackson.databind)   // reads the gate's JSON output
}

// The plugin classes load in the user's Gradle daemon, which may run on Java 17 (Gradle 8/9's minimum); logast itself
// needs 21+ and only runs in the worker process (see LogastTask).
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
java { disableAutoTargetJvm() }   // it depends on :cli (Java 21): only the worker loads those classes

gradlePlugin {
    plugins {
        create("logast") {
            id = "dev.logast"
            implementationClass = "dev.logast.gradle.LogastPlugin"
            displayName = "logast"
            description = "Scans a Spring Boot service with logast and gates CI on new findings against a baseline"
        }
    }
}

// TestKit functional tests: each starts a Gradle build and a scan worker (about 10-20 s each), so they run in their own
// task like :cli:e2e: `./gradlew :gradle-plugin:functionalTest`; part of `check` on CI (CI=true) or with -Pe2e.
val functionalTest = sourceSets.create("functionalTest")
gradlePlugin.testSourceSets(functionalTest, sourceSets.test.get())
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

// The init script install.sh puts in ~/.gradle/init.d/ (#122), with this version: the functional tests run it (the cli
// distribution ships the same file in gradle/).
val initScript = tasks.register<Copy>("initScript") {
    val v = project.version.toString()
    inputs.property("logastVersion", v)
    from("src/init/logast.gradle")
    into(layout.buildDirectory.dir("init-script"))
    filter { line: String -> line.replace("@LOGAST_VERSION@", v) }
}

// The sample build the tests write compiles against the fixture's Spring classpath (no download in TestKit's own
// Gradle home): the same jars the e2e tests use.
val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Gradle TestKit: a sample build applies dev.logast; logastScan, logastBaseline, logastCheck, logastSarif, logastApply"
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(":fixtures:sample-app:writeClasspath", initScript)
    // the init-script tests resolve the plugin from a repository, as a machine with install.sh does from Maven Local
    dependsOn(":core:publishAllPublicationsToLogastTestRepository", ":cli:publishAllPublicationsToLogastTestRepository",
        "publishAllPublicationsToLogastTestRepository")
    systemProperty("logast.initScript", layout.buildDirectory.file("init-script/logast.gradle").get().asFile.absolutePath)
    systemProperty("logast.testRepo", rootProject.layout.buildDirectory.dir("logast-test-repo").get().asFile.absolutePath)
    systemProperty("logast.version", project.version.toString())
    systemProperty("logast.fixture.classpath",
        project(":fixtures:sample-app").layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.test { useJUnitPlatform() }
tasks.named<JavaCompile>(functionalTest.compileJavaTaskName) { options.release = 21 }

if (providers.gradleProperty("e2e").isPresent || providers.environmentVariable("CI").isPresent) {
    tasks.check { dependsOn(functionalTestTask) }
}
