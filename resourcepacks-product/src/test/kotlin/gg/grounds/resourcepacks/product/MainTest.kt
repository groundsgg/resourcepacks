package gg.grounds.resourcepacks.product

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {
    @Test
    fun `cli accepts each required explicit option exactly once`() {
        assertEquals(
            ReleaseInputs(
                PublicationIdentity(PublicationType.RELEASE, "v0.0.0", "0.0.0", "a".repeat(40)),
                Path.of("/tmp/release"),
            ),
            ReleaseCli.parse(
                arrayOf(
                    "--publication-type",
                    "release",
                    "--publication-id",
                    "v0.0.0",
                    "--version",
                    "0.0.0",
                    "--commit",
                    "a".repeat(40),
                    "--output",
                    "/tmp/release",
                )
            ),
        )
    }

    @Test
    fun `cli rejects unknown duplicate missing empty and positional arguments`() {
        listOf(
                arrayOf("--wat", "x"),
                arrayOf(
                    "--publication-type",
                    "release",
                    "--publication-id",
                    "v0.0.0",
                    "--version",
                    "0.0.0",
                    "--version",
                    "0.0.0",
                    "--commit",
                    "a".repeat(40),
                    "--output",
                    "/tmp/out",
                ),
                arrayOf("--publication-type", "release"),
                arrayOf(
                    "--publication-type",
                    "release",
                    "--publication-id",
                    "v0.0.0",
                    "--version",
                    "",
                    "--commit",
                    "a".repeat(40),
                    "--output",
                    "/tmp/out",
                ),
                arrayOf(
                    "--publication-type",
                    "build",
                    "--publication-id",
                    "v0.0.0",
                    "--version",
                    "0.0.0",
                    "--commit",
                    "a".repeat(40),
                    "--output",
                    "/tmp/out",
                ),
                arrayOf("positional"),
            )
            .forEach { args ->
                assertFailsWith<IllegalArgumentException> { ReleaseCli.parse(args) }
            }
    }
}
