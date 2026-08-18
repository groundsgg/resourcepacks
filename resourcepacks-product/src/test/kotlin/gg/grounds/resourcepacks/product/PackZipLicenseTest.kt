package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class PackZipLicenseTest {
    @Test
    fun `release zips embed the matching artwork licence files`() {
        val parent = Files.createTempDirectory("pack-zip-license-")
        val scratch = SecureOwnedDirectory.create(parent, ".pack-license-scratch-")
        try {
            ProductGraph.secureReleaseInputs().use { secured ->
                val built = ReleasePackComposer.build(secured.packs, scratch, PackComposerHooks())
                val root =
                    generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) {
                            it.parent
                        }
                        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
                assertZipLicense(
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.CONTENT }.scratchFile
                    ),
                    root.resolve("art/content/LICENSE"),
                    "assets/grounds/legal/content.txt",
                )
                assertZipLicense(
                    scratch.stablePath.resolve(
                        built.single { it.pack.role == PackRole.PLATFORM }.scratchFile
                    ),
                    root.resolve("art/platform/LICENSE"),
                    "assets/grounds/legal/platform.txt",
                )
            }
        } finally {
            scratch.close()
            parent.toFile().deleteRecursively()
        }
    }

    private fun assertZipLicense(
        zip: java.nio.file.Path,
        license: java.nio.file.Path,
        entry: String,
    ) {
        val expected = Files.readAllBytes(license)
        val actual =
            ZipFile(zip.toFile()).use { archive ->
                archive.getInputStream(assertNotNull(archive.getEntry(entry))).readAllBytes()
            }
        assertContentEquals(expected, actual, zip.fileName.toString())
    }
}
