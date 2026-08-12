import org.gradle.api.JavaVersion
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("gg.grounds.base-conventions") version "0.8.0" apply false
    kotlin("jvm") version "2.2.20" apply false
}

group = "gg.grounds"

val semanticVersion = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
val versionFromFile = layout.projectDirectory.file("version.txt").asFile.readText().trim()
require(semanticVersion.matches(versionFromFile)) { "version.txt must contain an ASCII SemVer value" }
version = versionFromFile

subprojects {
    apply(plugin = "gg.grounds.base-conventions")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    version = rootProject.version

    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }

    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_24)
    }
    configurations.configureEach {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    dependencyLocking { lockAllConfigurations() }
}
