plugins {
    `java-library`
    `maven-publish`   // the cli depends on it, and the Gradle plugin publishes the cli (#115)
}

description = "logast kb: facts of many repos in memory, the questions over them, and the MCP server (#174)"

dependencies {
    api(project(":facts"))
    implementation(libs.jackson.yaml)
    implementation(libs.graalvm.polyglot)   // #201: code mode
    runtimeOnly(libs.graalvm.js)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}
