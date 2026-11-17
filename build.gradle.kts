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
val publicationType = providers.gradleProperty("publicationType").orNull
val publicationId = providers.gradleProperty("publicationId").orNull
val provenanceCommit = providers.gradleProperty("provenanceCommit").orNull
val exactEdgeBuildVersion =
    explicitPackSetVersion?.let {
        Regex("0\\.0\\.0-edge\\.[1-9][0-9]*\\.g([0-9a-f]{12})").matchEntire(it)
    }
val exactBuildIdentity =
    publicationType == "build" &&
        publicationId != null &&
        publicationId.matches(Regex("[0-9a-f]{40}")) &&
        publicationId == provenanceCommit &&
        exactEdgeBuildVersion?.groupValues?.get(1) == publicationId.take(12)
require(
    explicitPackSetVersion == null ||
        explicitPackSetVersion == versionFromFile ||
        exactBuildIdentity
) {
    "packSetVersion must equal version.txt or an exact build identity."
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

// Build-time tooling only. Normal neutral builds do not resolve this configuration.
val npcImporter by configurations.creating
repositories {
    mavenCentral()
    providers.gradleProperty("npcRepository").orNull?.let { location ->
        exclusiveContent {
            forRepository { maven { url = uri(location) } }
            filter { listOf("npc-core", "npc-importer", "npc-cli").forEach { includeModule("gg.grounds", it) } }
        }
    }
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/library-npc")
        credentials {
            username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
            password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
        }
    }
}
providers.gradleProperty("npcImporterVersion").orNull?.let { version ->
    dependencies.add(npcImporter.name, "gg.grounds:npc-cli:$version")
}
val npcImporterClasspath = providers.gradleProperty("npcImporterClasspath").orElse(provider {
    check(providers.gradleProperty("npcImporterVersion").isPresent) {
        "Licensed NPC imports require npcImporterVersion or an explicit npcImporterClasspath"
    }
    npcImporter.asPath
})

val npcSourceDirectory = providers.systemProperty("grounds.npc.sources")
    .orElse(providers.gradleProperty("npcSourceDirectory"))
val npcOutput = layout.buildDirectory.dir("generated/npc-models")
val npcGeometryMode = providers.gradleProperty("npcGeometryMode").orElse("rigid")
val npcCompositePolicy = providers.gradleProperty("npcCompositePolicy")
tasks.register<Exec>("compileNpcModels") {
    inputs.files(layout.projectDirectory.file("tools/compile_npc_models.py"), layout.projectDirectory.file("tools/GroundsNpcImporter.java"))
    inputs.property("npcSourceDirectory", npcSourceDirectory.orElse(""))
    inputs.property("npcGeometryMode", npcGeometryMode)
    inputs.property("npcCompositePolicy", npcCompositePolicy.orElse(""))
    npcCompositePolicy.orNull?.let { inputs.file(file(it)) }
    npcSourceDirectory.orNull?.let {
        inputs.dir(file(it))
        inputs.files(provider { npcImporterClasspath.get().split(java.io.File.pathSeparator).map(::file) })
        inputs.property("npcImporterClasspathOrder", npcImporterClasspath.map { it.split(java.io.File.pathSeparator).map { entry -> file(entry).canonicalPath } })
    }
    outputs.dir(npcOutput)
    doFirst {
        val arguments = mutableListOf("python3", layout.projectDirectory.file("tools/compile_npc_models.py").asFile.absolutePath, "--output", npcOutput.get().asFile.absolutePath)
        arguments.addAll(listOf("--geometry-mode", npcGeometryMode.get()))
        npcCompositePolicy.orNull?.let { arguments.addAll(listOf("--composite-policy", file(it).absolutePath)) }
        npcSourceDirectory.orNull?.let { arguments.addAll(listOf("--sources", it, "--importer-classpath", npcImporterClasspath.get())) }
        commandLine(arguments)
    }
}

// Exercise the real importer boundary, including all retained product optimizer regressions.
tasks.register<Exec>("verifyNpcCompiler") {
    inputs.dir(layout.projectDirectory.dir("tools"))
    doFirst {
        environment("NPC_IMPORTER_CLASSPATH", npcImporterClasspath.get())
        commandLine("python3", "-m", "unittest", "discover", "-s", "tools")
    }
}
