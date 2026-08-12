import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api("gg.grounds:library-gui:0.6.0")
    api("gg.grounds:scene-format:0.1.0")

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

java { withSourcesJar() }

val generatedCatalogSources = layout.buildDirectory.dir("generated/sources/catalog/kotlin")

val generateCatalogBuildInfo by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("version.txt"))
    into(generatedCatalogSources.map { it.dir("gg/grounds/resourcepacks/catalog") })
    rename { "CatalogBuildInfo.kt" }
    filter { line ->
        when (line) {
            rootProject.version.toString() ->
                "package gg.grounds.resourcepacks.catalog\n\nobject CatalogBuildInfo {\n    const val VERSION = \"$line\"\n}"
            else -> line
        }
    }
}

kotlin { sourceSets.named("main") { kotlin.srcDir(generatedCatalogSources) } }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCatalogBuildInfo)
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    dependsOn(generateCatalogBuildInfo)
}

val forbiddenRuntimeCoordinateFragments =
    listOf(
        "paper",
        "bukkit",
        "minestom",
        "velocity",
        "config",
        "portal",
        "testkit",
        "test-support",
        "junit",
        "kotest",
        "resource-pack-builder",
    )

tasks.register("verifyCatalogRuntimeClasspath") {
    group = "verification"
    description =
        "Rejects server, configuration, portal, test, and product dependencies from the catalog runtime graph."
    doLast {
        val forbiddenCoordinates =
            configurations.runtimeClasspath
                .get()
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { it.moduleVersion }
                .map { "${it.group}:${it.name}:${it.version}" }
                .filter { coordinate ->
                    val normalizedCoordinate = coordinate.lowercase()
                    forbiddenRuntimeCoordinateFragments.any(normalizedCoordinate::contains)
                }

        check(forbiddenCoordinates.isEmpty()) {
            "Forbidden catalog runtime components: ${forbiddenCoordinates.sorted().joinToString()}."
        }
    }
}

tasks.named("check") { dependsOn("verifyCatalogRuntimeClasspath") }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "resourcepacks-catalog"
        }
    }
}
