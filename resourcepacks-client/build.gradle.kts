dependencies {
    api(project(":resourcepacks-contract"))
    testImplementation(kotlin("test"))
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(tasks.named("jar"))
    doFirst {
        systemProperty(
            "client.jar",
            tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile.absolutePath,
        )
    }
}
