plugins { application }

application { mainClass = "gg.grounds.resourcepacks.product.MainKt" }

val buildPackSet by
    tasks.registering(JavaExec::class) {
        group = "build"
        description = "Builds one transactional four-artifact PackSet release from explicit inputs."
        dependsOn(":resourcepacks-catalog:jar", "jar")
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        val required =
            listOf("packSetVersion", "provenanceCommit", "provenanceTag", "releaseOutput")
        doFirst {
            val values = required.associateWith { providers.gradleProperty(it).orNull }
            check(values.values.none { it.isNullOrEmpty() }) {
                "buildPackSet requires explicit properties: ${required.joinToString()}."
            }
            args(
                "--version",
                values.getValue("packSetVersion")!!,
                "--commit",
                values.getValue("provenanceCommit")!!,
                "--tag",
                values.getValue("provenanceTag")!!,
                "--output",
                values.getValue("releaseOutput")!!,
            )
        }
    }

dependencies {
    implementation(project(":resourcepacks-catalog"))
    implementation("gg.grounds:resource-pack-builder:0.1.0")
    implementation("tools.jackson.core:jackson-databind:3.1.5")

    testImplementation("gg.grounds:resource-pack-testkit:0.1.0")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}
