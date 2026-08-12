plugins { application }

dependencies {
    implementation(project(":resourcepacks-catalog"))
    implementation("gg.grounds:resource-pack-builder:0.1.0")
    implementation("tools.jackson.core:jackson-databind:3.1.5")

    testImplementation("gg.grounds:resource-pack-testkit:0.1.0")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}
