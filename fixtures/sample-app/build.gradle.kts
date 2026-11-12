plugins { java }

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

// Writes the compile classpath so logast tests (and `logast scan`) can type-attribute the fixture.
val writeClasspath by tasks.registering {
    val cp = configurations.compileClasspath
    val out = layout.buildDirectory.file("classpath.txt")
    inputs.files(cp)
    outputs.file(out)
    doLast {
        out.get().asFile.writeText(cp.get().files.joinToString("\n") { it.absolutePath })
    }
}
