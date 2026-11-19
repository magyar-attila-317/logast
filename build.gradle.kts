subprojects {
    // Every jar carries the license and the NOTICE that names logast as the source (Apache-2.0 section 4).
    tasks.withType<Jar>().configureEach {
        into("META-INF") { from(rootProject.file("LICENSE"), rootProject.file("NOTICE")) }
    }
    // Libraries other projects consume (the starter, the test capture, the Gradle plugin with core and cli) publish to
    // Maven Local, or to a company repository when -PrepoUrl=... (or REPO_URL) is set, with repoUser/repoPassword
    // (REPO_USER/REPO_PASSWORD).
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                // java-gradle-plugin creates its own publications (the jar and the plugin marker)
                if (!plugins.hasPlugin("java-gradle-plugin")) {
                    create<MavenPublication>("maven") { from(components["java"]) }
                }
                withType<MavenPublication>().configureEach {
                    pom {
                        name.set(project.name)
                        description.set(project.description ?: "logast " + project.name)
                        url.set("https://github.com/magyar-attila-317/logast")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                    }
                }
            }
            repositories {
                // the Gradle plugin's functional tests resolve it from here, as the init script does from Maven Local (#122)
                maven {
                    name = "logastTest"
                    setUrl(rootProject.layout.buildDirectory.dir("logast-test-repo"))
                }
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
