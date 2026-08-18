import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.publish.maven.MavenPublication

plugins { `maven-publish` }

java { withSourcesJar() }

dependencies {
    implementation("tools.jackson.core:jackson-core:3.1.5")
    testImplementation(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "resourcepacks-contract"
            pom {
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
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

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    from(rootProject.layout.projectDirectory.file("LICENSES/Apache-2.0.txt")) {
        into("META-INF")
        rename { "LICENSE" }
    }
}

val stageExactMavenPublication by
    tasks.registering {
        group = "publishing"
        description =
            "Stages exactly the four immutable Contract Maven publication files for the decision gate."
        dependsOn(
            tasks.named("jar"),
            tasks.named("sourcesJar"),
            tasks.named("generatePomFileForMavenJavaPublication"),
            tasks.named("generateMetadataFileForMavenJavaPublication"),
        )
        doLast {
            val versionText = project.version.toString()
            val artifact = "resourcepacks-contract"
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(tasks.named("jar"))
    doFirst {
        systemProperty(
            "contract.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
}
