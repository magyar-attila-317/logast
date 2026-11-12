subprojects {
    // Libraries other projects consume (the starter, the test capture) publish to Maven Local, or to a company
    // repository when -PrepoUrl=... (or REPO_URL) is set, with repoUser/repoPassword (REPO_USER/REPO_PASSWORD).
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "logast " + project.name)
                    }
                }
            }
            repositories {
                val url = (findProperty("repoUrl") as String?) ?: System.getenv("REPO_URL")
                if (url != null) {
                    maven {
                        name = "company"
                        setUrl(url)
                        credentials {
                            username = (findProperty("repoUser") as String?) ?: System.getenv("REPO_USER")
                            password = (findProperty("repoPassword") as String?) ?: System.getenv("REPO_PASSWORD")
                        }
                    }
                }
            }
        }
    }
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion = JavaLanguageVersion.of(25) }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
            options.release = 21   // logast runs on Java 21+; the starter and test-capture override with 17
        }
    }
}
