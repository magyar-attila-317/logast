plugins {
    `java-library`
    `maven-publish`   // the Gradle plugin depends on it (#115)
}

dependencies {
    api(platform(libs.rewrite.bom))
    api(libs.rewrite.java)
    runtimeOnly(libs.rewrite.java21)   // OpenRewrite picks the parser matching the running JVM: 21-24
    runtimeOnly(libs.rewrite.java25)           // 25+
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    api(project(":facts"))   // #152: what `logast export` writes
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

// Lombok for CompileCheckTest only: the compile gate runs it as annotation processor (#76)
val testLombok by configurations.creating
dependencies {
    testLombok(platform(libs.spring.boot.bom))
    testLombok("org.projectlombok:lombok")
    testLombok("org.slf4j:slf4j-api")   // the type of the logger @Slf4j generates
}

val fixture = project(":fixtures:sample-app")

tasks.test {
    useJUnitPlatform()
    dependsOn(":fixtures:sample-app:writeClasspath", ":logging-support:classes")
    systemProperty("logast.support.classes", project(":logging-support").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath)
    inputs.files(testLombok)
    systemProperty("logast.test.lombok", testLombok.asPath)
    systemProperty("logast.fixture.root", fixture.projectDir.absolutePath)
    systemProperty("logast.fixture.classpath", fixture.layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
    // RuleLedgerTest reads e2e/before and the e2e snapshots, and the fixture's sources
    inputs.dir(rootProject.file("e2e"))
    inputs.dir(fixture.projectDir.resolve("src"))
    inputs.file(fixture.projectDir.resolve("logast.yaml"))
    maxHeapSize = "2g"
}

tasks.register("printRuntimeClasspath") {
    val cp = configurations.runtimeClasspath
    doLast { println(cp.get().files.joinToString("\n")) }
}

// logging-support sources ship inside the tool so `support.mode: vendor` can copy them into a service
tasks.processResources {
    from(project(":logging-support").layout.projectDirectory.dir("src/main/java/dev/logast/support")) {
        into("logast-vendor")
        include("*.java")
    }
    // #142: the pack pages' rule tables are the rule help of the fleet report
    from(rootProject.layout.projectDirectory.dir("docs/packs")) {
        into("rule-docs")
        include("*.md")
    }
}
