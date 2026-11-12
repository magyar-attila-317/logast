plugins { `java-library` }

dependencies {
    api(platform(libs.rewrite.bom))
    api(libs.rewrite.java)
    runtimeOnly(libs.rewrite.java21)   // OpenRewrite picks the parser matching the running JVM: 21-24
    runtimeOnly(libs.rewrite.java25)           // 25+
    api(libs.jackson.databind)
    api(libs.jackson.yaml)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

val fixture = project(":fixtures:sample-app")

tasks.test {
    useJUnitPlatform()
    dependsOn(":fixtures:sample-app:writeClasspath", ":logging-support:classes")
    systemProperty("logast.support.classes", project(":logging-support").layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath)
    systemProperty("logast.fixture.root", fixture.projectDir.absolutePath)
    systemProperty("logast.fixture.classpath", fixture.layout.buildDirectory.file("classpath.txt").get().asFile.absolutePath)
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
}
