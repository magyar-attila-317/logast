plugins { application }

dependencies {
    implementation(project(":core"))
    implementation(libs.picocli)
    runtimeOnly(project(":test-capture"))   // shipped in lib/; `logast verify` adds it to a service's test runtime
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("logast-version.properties") { expand("version" to v) }
}

// release zip: bin/, lib/, plus docs, examples and the Claude Code skill
distributions {
    main {
        contents {
            from(rootProject.file("README.md"))
            from(rootProject.file("docs")) { into("docs") }
            from(rootProject.file("examples")) { into("examples") }
            from(rootProject.file("docs/llm-workflow.md")) { into("claude-skill/logast"); rename { "SKILL.md" } }
        }
    }
}

application {
    mainClass = "dev.logast.cli.Main"
    applicationName = "logast"
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=50")
}
