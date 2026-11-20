plugins { java }

dependencies {
    implementation("dev.logast:logging-support:0.2.0")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework:spring-jdbc")
    implementation("io.micrometer:micrometer-observation")
    implementation("io.micrometer:context-propagation")
}
