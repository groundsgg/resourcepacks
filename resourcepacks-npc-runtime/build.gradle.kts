import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":resourcepacks-catalog"))
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The allocation regression must not race background JIT compilation on busy CI workers.
// This changes only test JVMs; the measured allocation budget remains unchanged.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach { jvmArgs("-Xbatch") }

java { withSourcesJar() }

// Purchased bone/animation data stays in the private renderer variant. The public
// catalogue contains only neutral asset declarations, never this model payload.
sourceSets.named("main") {
    resources.srcDir(
        rootProject.layout.buildDirectory.dir("generated/npc-models/catalog-resources")
    )
}

tasks.named("processResources") { dependsOn(rootProject.tasks.named("compileNpcModels")) }

tasks.named<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    dependsOn(rootProject.tasks.named("compileNpcModels"))
    // Sources archives contain our renderer code, never purchased generated model data.
    exclude("**/*.json")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "resourcepacks-npc-runtime"
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

// Public Maven packages contain renderer code and an empty index. Licensed model
// resources may only be bundled into the private Stage host/preview artifacts.
val verifyNeutralNpcPublication =
    tasks.register("verifyNeutralNpcPublication") {
        dependsOn(tasks.named("jar"))
        doLast {
            JarFile(tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile)
                .use { jar ->
                    val json =
                        jar.entries()
                            .asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".json") }
                            .toList()
                    check(
                        json.map { it.name }.toSet() ==
                            setOf(
                                "gg/grounds/resourcepacks/npc/index.json",
                                "gg/grounds/resourcepacks/npc/pack-digests.json",
                            )
                    ) {
                        "Purchased NPC resources must not be published to Maven"
                    }
                    val index =
                        jar.getInputStream(
                                jar.getJarEntry("gg/grounds/resourcepacks/npc/index.json")
                            )
                            .bufferedReader()
                            .use { it.readText() }
                    check(index.trim() == """{"models":[],"schemaVersion":1}""") {
                        "Public NPC runtime must contain an empty model index"
                    }
                }
        }
    }

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    dependsOn(verifyNeutralNpcPublication)
}

val stageExactMavenPublication by
    tasks.registering {
        group = "publishing"
        description =
            "Stages exactly the four immutable NPC runtime Maven publication files for the decision gate."
        dependsOn(
            verifyNeutralNpcPublication,
            tasks.named("jar"),
            tasks.named("sourcesJar"),
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        doLast {
            val versionText = project.version.toString()
            val artifact = "resourcepacks-npc-runtime"
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
