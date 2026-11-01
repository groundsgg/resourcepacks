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
}
