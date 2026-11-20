import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    `java-library`
    `maven-publish`
}

val dialogueAssets by
    configurations.creating {
        isCanBeConsumed = false
        isTransitive = false
    }

dependencies { dialogueAssets("gg.grounds:dialogue-minestom:1.1.2") }

val generatedResources = layout.buildDirectory.dir("generated/dialogue-resources")
val importDialogueAssets by
    tasks.registering(Exec::class) {
        inputs.files(dialogueAssets)
        inputs.file(rootProject.file("tools/import_dialogue_assets.py"))
        inputs.dir("src/main/resources/grounds-dialogue/theme")
        outputs.dir(generatedResources)
        doFirst {
            commandLine(
                "python3",
                rootProject.file("tools/import_dialogue_assets.py"),
                dialogueAssets.singleFile,
                file("src/main/resources/grounds-dialogue/theme"),
                generatedResources.get().asFile,
            )
        }
    }

sourceSets.main { resources.srcDir(generatedResources) }

tasks.processResources { dependsOn(importDialogueAssets) }

java { withSourcesJar() }

tasks.named("sourcesJar") { dependsOn(importDialogueAssets) }

publishing {
    publications { create<MavenPublication>("mavenJava") { from(components["java"]) } }
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
            "Stages exactly the four immutable dialogue runtime Maven publication files for the decision gate."
        dependsOn(
            tasks.named("jar"),
            tasks.named("sourcesJar"),
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        doLast {
            val versionText = project.version.toString()
            val artifact = "resourcepacks-dialogue-runtime"
            val target = layout.buildDirectory.dir("release-maven-staging").get().asFile.toPath()
            check(Files.notExists(target)) { "Maven staging directory already exists: $target" }
            val versionDirectory = target.resolve("gg/grounds/$artifact/$versionText")
            Files.createDirectories(versionDirectory)
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
                .forEach { (source, destination) ->
                    Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
        }
    }
