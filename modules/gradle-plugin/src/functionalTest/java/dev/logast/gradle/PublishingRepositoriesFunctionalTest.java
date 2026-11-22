package dev.logast.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #127: install.sh offers the repositories this machine's Gradle already knows, and publishes the support jar to the
 * one picked by name, with the credentials configured on it. The script is logast's own gradle/publishing.gradle,
 * applied to a small build shaped like logast's (settings hand over their repositories, a library publishes). Gradle's
 * user home is a temp folder (TestKit's dir) whose init.d declares what a company init script would: a file-based
 * release repository with credentials (settings), a group repository and Maven Central / Maven Local (projects).
 */
class PublishingRepositoriesFunctionalTest {
    static final Path SCRIPT = Path.of(System.getProperty("logast.publishingScript"));

    @TempDir
    Path project;
    @TempDir
    Path home;
    @TempDir
    Path releases;
    @TempDir
    Path staging;
    @TempDir
    Path mavenLocal;

    @BeforeEach
    void buildAndInitScript() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), """
                rootProject.name = 'sample'
                include 'lib'
                gradle.ext.logastSettingsRepositories = [dependencyResolutionManagement.repositories, pluginManagement.repositories]
                """);
        Files.writeString(project.resolve("build.gradle"), "apply from: '" + SCRIPT.toString().replace("\\", "/") + "'\n");
        Files.createDirectories(project.resolve("lib"));
        Files.writeString(project.resolve("lib/build.gradle"), """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                }
                group = 'com.example'
                version = '1.0.0'
                publishing { publications { lib(MavenPublication) { from components.java } } }
                """);
        Files.createDirectories(home.resolve("init.d"));
        Files.writeString(home.resolve("init.d/company.gradle"), """
                beforeSettings { s ->
                    s.dependencyResolutionManagement.repositories {
                        maven {
                            name = 'acmeReleases'
                            url = uri('%s')
                            credentials { username = 'deployer'; password = 'not-a-secret' }
                        }
                        maven { name = 'acmeStaging'; url = uri('%s') }
                    }
                }
                allprojects {
                    repositories {
                        maven { name = 'acmeGroup'; url = 'https://nexus.example.org/repository/maven-public/' }
                        mavenCentral()
                        mavenLocal()
                    }
                }
                """.formatted(releases.toUri(), staging.toUri()));
    }

    @Test
    void theRepositoriesGradleKnowsAreListedWithoutThePublicOnes() {
        String out = gradle("-q", "logastRepositories").build().getOutput();
        assertThat(out).as("from the settings, added by the init script").contains("acmeReleases\t" + releases.toFile().toURI())
                .as("from the projects").contains("acmeGroup\thttps://nexus.example.org/repository/maven-public/")
                .doesNotContain("MavenRepo").doesNotContain("repo.maven.apache.org").doesNotContain("MavenLocal");
    }

    @Test
    void publishingByNameReusesTheRepositoryWithItsCredentials() {
        String target = gradle("-q", "logastPublishTarget", "-PrepoName=acmeReleases").build().getOutput();
        assertThat(target).as("the publishing repository: its URL and the credentials configured on it\n" + target)
                .contains("company\t" + releases.toFile().toURI())
                .contains("\tdeployer\tpassword set");
    }

    /** Gradle refuses credentials on a file repository: the upload itself goes to one configured without them. */
    @Test
    void publishingByNameUploadsThere() {
        gradle(":lib:publishAllPublicationsToCompanyRepository", "-PrepoName=acmeStaging").build();
        assertThat(staging.resolve("com/example/lib/1.0.0/lib-1.0.0.jar")).as("published there").exists();
    }

    @Test
    void anUnknownNameFailsWithWhereToLook() {
        BuildResult r = gradle("-q", "logastPublishTarget", "-PrepoName=nexus").buildAndFail();
        assertThat(r.getOutput()).contains("no repository named 'nexus'").contains("logastRepositories");
    }

    @Test
    void aUrlPublishesWithTheGivenUser() {
        String target = gradle("-q", "logastPublishTarget", "-PrepoUrl=https://nexus.example.org/repository/releases/",
                "-PrepoUser=ci").build().getOutput();
        assertThat(target).contains("company\thttps://nexus.example.org/repository/releases/\tci\t-");
    }

    private GradleRunner gradle(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("-Dmaven.repo.local=" + mavenLocal);
        all.add("--stacktrace");
        return GradleRunner.create().withProjectDir(project.toFile()).withTestKitDir(home.toFile()).withArguments(all).forwardOutput();
    }
}
