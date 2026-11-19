subprojects {
    // Every jar carries the license and the NOTICE that names logast as the source (Apache-2.0 section 4).
    tasks.withType<Jar>().configureEach {
        into("META-INF") { from(rootProject.file("LICENSE"), rootProject.file("NOTICE")) }
    }
    // Libraries other projects consume (the starter, the test capture, the Gradle plugin with core and cli) publish to
    // Maven Local, or to a company repository: -PrepoUrl=... (or REPO_URL) with repoUser/repoPassword
    // (REPO_USER/REPO_PASSWORD), or -PrepoName=<a repository this machine's Gradle already knows> (#127,
    // gradle/publishing.gradle).
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications {
                // java-gradle-plugin creates its own publications (the jar and the plugin marker). Our own name, not
                // "maven": a company init script that sets up a "maven" publication for every project would otherwise
                // add the same jar to ours a second time
                if (!plugins.hasPlugin("java-gradle-plugin")) {
                    create<MavenPublication>("logast") { from(components["java"]) }
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
                // "company" (-PrepoUrl, -PrepoName): gradle/publishing.gradle
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

// #127: where logast's libraries publish besides Maven Local; the repositories install.sh offers
apply(from = "gradle/publishing.gradle")
