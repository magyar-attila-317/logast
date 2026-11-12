plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach { (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true) }

dependencies {
    api(platform(libs.spring.boot.bom))
    api("org.slf4j:slf4j-api")
    api("io.micrometer:micrometer-observation")
    implementation("io.micrometer:context-propagation")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-web")
    compileOnly("io.micrometer:micrometer-tracing")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    testImplementation("org.springframework:spring-web")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

tasks.test { useJUnitPlatform() }
