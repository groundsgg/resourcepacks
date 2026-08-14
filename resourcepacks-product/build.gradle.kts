import java.security.MessageDigest

plugins { application }

application { mainClass = "gg.grounds.resourcepacks.product.MainKt" }

application { applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") }

val catalogJar =
    project(":resourcepacks-catalog").tasks.named<org.gradle.jvm.tasks.Jar>("jar").flatMap {
        it.archiveFile
    }
val generatedCatalogExpectationDirectory =
    layout.buildDirectory.dir("generated/sources/catalogExpectation/kotlin")
val generatedCatalogExpectation =
    generatedCatalogExpectationDirectory.map {
        it.file("gg/grounds/resourcepacks/product/GeneratedCatalogArtifactExpectation.kt")
    }

val generateCatalogArtifactExpectation by
    tasks.registering {
        dependsOn(catalogJar)
        inputs.file(catalogJar)
        outputs.file(generatedCatalogExpectation)
        doLast {
            val artifact = catalogJar.get().asFile
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val target = generatedCatalogExpectation.get().asFile
            target.parentFile.mkdirs()
            target.writeText(
                """
                package gg.grounds.resourcepacks.product

                internal object GeneratedCatalogArtifactExpectation {
                    const val SIZE: Long = ${artifact.length()}L
                    const val SHA256: String = "$sha256"
                }
                """
                    .trimIndent() + "\n"
            )
        }
    }

kotlin { sourceSets.named("main") { kotlin.srcDir(generatedCatalogExpectationDirectory) } }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateCatalogArtifactExpectation)
}

val buildPackSet by
    tasks.registering(JavaExec::class) {
        group = "build"
        description = "Builds one transactional four-artifact PackSet release from explicit inputs."
        dependsOn(":resourcepacks-catalog:jar", "jar")
        classpath = files(catalogJar) + sourceSets.main.get().runtimeClasspath
        mainClass.set(application.mainClass)
        jvmArgs("--enable-native-access=ALL-UNNAMED")
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
    testImplementation("org.yaml:snakeyaml:2.6")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
