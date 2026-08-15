package gg.grounds.resourcepacks.product

import java.nio.file.Path

internal object ReleaseTestContext {
    val version: String by lazy {
        requireNotNull(System.getProperty("catalog.version")) {
            "Gradle must provide the authoritative catalog version to product tests."
        }
    }

    val catalogJar: Path by lazy {
        Path.of(
            requireNotNull(System.getProperty("catalog.jar")) {
                "Gradle must provide the exact catalog JAR to product tests."
            }
        )
    }

    fun inputs(output: Path, commit: String = "a".repeat(40)): ReleaseInputs =
        ReleaseInputs(version, commit, "v$version", output)
}
