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
