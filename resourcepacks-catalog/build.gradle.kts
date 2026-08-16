import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

val generateCatalogBuildInfo by
    tasks.registering(Copy::class) {
        inputs.property("catalogVersion", provider { rootProject.version.toString() })
        from(rootProject.layout.projectDirectory.file("version.txt"))
        into(generatedCatalogSources.map { it.dir("gg/grounds/resourcepacks/catalog") })
        rename { "CatalogBuildInfo.kt" }
        filter {
            """
        package gg.grounds.resourcepacks.catalog

        import gg.grounds.scene.format.AssetCatalog
        import gg.grounds.scene.format.CatalogId
        import gg.grounds.scene.format.CatalogVersionRange
        import java.util.Collections

        object GroundsAssetCatalog {
            private const val CATALOG_VERSION = "${rootProject.version}"

            val catalog: AssetCatalog =
                AssetCatalog(
                    CatalogId("grounds:assets"),
                    CATALOG_VERSION,
                    CatalogVersionRange(CatalogId("grounds:resourcepacks"), CATALOG_VERSION, CATALOG_VERSION),
                    Collections.unmodifiableMap(linkedMapOf()),
                )
        }
        """
                .trimIndent()
        }
    }

kotlin { sourceSets.named("main") { kotlin.srcDir(generatedCatalogSources) } }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCatalogBuildInfo)
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach { dependsOn(generateCatalogBuildInfo) }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(tasks.named("jar"))
    systemProperty("catalog.version", rootProject.version.toString())
    doFirst {
        systemProperty(
            "catalog.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
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
    repositories {
        maven {
            name = "ReleaseStaging"
            url = layout.buildDirectory.dir("release-maven-staging").get().asFile.toURI()
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/groundsgg/resourcepacks")
            credentials {
                username =
                    providers.gradleProperty("github.user").orNull
                        ?: System.getenv("GITHUB_ACTOR")
                        ?: ""
                password =
                    providers.gradleProperty("github.token").orNull
                        ?: System.getenv("GITHUB_TOKEN")
                        ?: ""
            }
        }
    }
}

val stageExactMavenPublication by
    tasks.registering {
        group = "publishing"
        description =
            "Stages exactly the four immutable Maven publication files for the decision gate."
        dependsOn(
            tasks.named("jar"),
            tasks.named("sourcesJar"),
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        doLast {
            val versionText = project.version.toString()
            val artifact = "resourcepacks-catalog"
            val target = layout.buildDirectory.dir("release-maven-staging").get().asFile.toPath()
            check(Files.notExists(target)) { "Maven staging directory already exists: $target" }
            val versionDirectory = target.resolve("gg/grounds/$artifact/$versionText")
            Files.createDirectories(versionDirectory)
            val files =
                mapOf(
                    layout.buildDirectory
                        .file("libs/$artifact-$versionText.jar")
                        .get()
                        .asFile
                        .toPath() to versionDirectory.resolve("$artifact-$versionText.jar"),
                    layout.buildDirectory
                        .file("libs/$artifact-$versionText-sources.jar")
                        .get()
                        .asFile
                        .toPath() to versionDirectory.resolve("$artifact-$versionText-sources.jar"),
                    layout.buildDirectory
                        .file("publications/mavenJava/pom-default.xml")
                        .get()
                        .asFile
                        .toPath() to versionDirectory.resolve("$artifact-$versionText.pom"),
                    layout.buildDirectory
                        .file("publications/mavenJava/module.json")
                        .get()
                        .asFile
                        .toPath() to versionDirectory.resolve("$artifact-$versionText.module"),
                )
            files.forEach { (source, destination) ->
                Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
