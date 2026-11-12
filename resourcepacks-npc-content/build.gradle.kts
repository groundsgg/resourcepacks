import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

plugins {
    `java-library`
    `maven-publish`
}

java { withSourcesJar() }

sourceSets.named("main") {
    resources.srcDir(
        rootProject.layout.buildDirectory.dir("generated/npc-models/catalog-resources")
    )
}

tasks.processResources { dependsOn(rootProject.tasks.named("compileNpcModels")) }

tasks.named<org.gradle.jvm.tasks.Jar>("sourcesJar") {
    dependsOn(rootProject.tasks.named("compileNpcModels"))
    exclude("**/*.json")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    dependsOn(tasks.jar)
    systemProperty("npcContentJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}

publishing {
    publications { create<MavenPublication>("mavenJava") { from(components["java"]) } }
    repositories {
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

// Public Maven content packages contain only a neutral index and digests. Licensed model
// resources may only be bundled into the private Stage host/preview artifacts.
val verifyNeutralNpcPublication =
    tasks.register("verifyNeutralNpcPublication") {
        dependsOn(tasks.named("jar"), tasks.named("sourcesJar"))
        doLast {
            JarFile(tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile)
                .use { jar ->
                    val entries = jar.entries().asSequence().filter { !it.isDirectory }.toList()
                    check(entries.map { it.name }.distinct().size == entries.size) {
                        "Purchased NPC resources must not be published with duplicate archive entries"
                    }
                    check(
                        entries.map { it.name }.toSet() ==
                            setOf(
                                "META-INF/MANIFEST.MF",
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
                        "Public NPC content must contain an empty model index"
                    }
                    val digests =
                        jar.getInputStream(
                                jar.getJarEntry("gg/grounds/resourcepacks/npc/pack-digests.json")
                            )
                            .bufferedReader()
                            .use { it.readText() }
                    check(digests.trim() == "{}") {
                        "Public NPC content must not include a private pack contract"
                    }
                }
            JarFile(
                    tasks
                        .named<org.gradle.jvm.tasks.Jar>("sourcesJar")
                        .get()
                        .archiveFile
                        .get()
                        .asFile
                )
                .use { jar ->
                    val entries = jar.entries().asSequence().filter { !it.isDirectory }.toList()
                    check(
                        entries.map { it.name }.distinct().size == entries.size &&
                            entries.all { it.name == "META-INF/MANIFEST.MF" }
                    ) {
                        "Purchased NPC resources must not be published in sources archives"
                    }
                }
        }
    }

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    dependsOn(verifyNeutralNpcPublication)
}

// Explicit local review destination; no remote publishing is configured here.
providers.gradleProperty("npcReviewRepository").orNull?.let { location ->
    publishing.repositories.maven {
        name = "Review"
        url = uri(location)
    }
}

tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenLocal>().configureEach {
    dependsOn(verifyNeutralNpcPublication)
}

val stageExactMavenPublication by
    tasks.registering {
        group = "publishing"
        description =
            "Stages exactly the four immutable NPC content Maven publication files for the decision gate."
        dependsOn(
            verifyNeutralNpcPublication,
            tasks.named("jar"),
            tasks.named("sourcesJar"),
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        doLast {
            val versionText = project.version.toString()
            val artifact = "resourcepacks-npc-content"
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
