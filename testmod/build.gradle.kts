plugins {
    alias(libs.plugins.felis.dam)
}

group = "io.github.joemama"
version = "1.0.0-alpha"

dependencies {
    implementation(project(":"))
}

tasks.processResources {
    filesMatching("mods.toml") {
        expand("version" to version)
    }
}