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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "resourcepacks-catalog"
        }
    }
}
