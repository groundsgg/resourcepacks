import org.gradle.api.publish.maven.MavenPublication

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
