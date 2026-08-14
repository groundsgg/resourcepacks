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

val semanticVersion =
    Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
    )
val versionFileContents = layout.projectDirectory.file("version.txt").asFile.readText()
val versionFromFile = versionFileContents.removeSuffix("\n")
require(semanticVersion.matches(versionFromFile) && versionFileContents == "$versionFromFile\n") {
    "version.txt must contain an exact ASCII SemVer value"
}
val explicitPackSetVersion = providers.gradleProperty("packSetVersion").orNull
require(explicitPackSetVersion == null || explicitPackSetVersion == versionFromFile) {
    "packSetVersion must equal version.txt."
}
version = explicitPackSetVersion ?: versionFromFile

tasks.register("verifyDependencyLocks") {
    group = "verification"
    description = "Verifies that every subproject has committed dependency lock state."
    doLast {
        subprojects.forEach { project ->
            val lockFile = project.layout.projectDirectory.file("gradle.lockfile").asFile
            check(lockFile.isFile && lockFile.readLines().any { it.isNotBlank() && !it.startsWith("#") }) {
                "${project.path} is missing committed dependency lock state"
            }
        }
    }
}

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

subprojects {
    tasks.named("check") { dependsOn(rootProject.tasks.named("verifyDependencyLocks")) }
}
