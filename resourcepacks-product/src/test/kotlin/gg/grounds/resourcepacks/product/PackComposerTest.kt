package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackComposerTest {
    @Test
    fun `build writes the exact physical pack entries and hash addressed names`() {
        val root = Files.createTempDirectory("pack-composer")
        try {
            val built = PackComposer.build(ProductGraph, root)

            assertEquals(listOf("grounds-content", "grounds-platform"), built.map { it.pack.id })
            val contentEntries = zipEntries(built.first().file)
            val platformEntries = zipEntries(built.last().file)
            assertEquals(
                listOf("assets/grounds/legal/content.txt", "pack.mcmeta"),
                contentEntries.map { it.name },
            )
            assertEquals(
                (ProductGraph.packs
                        .last()
                        .contributions
                        .flatMap { it.entries }
                        .map { it.path.value } + "pack.mcmeta" + "pack.png")
                    .sorted(),
                platformEntries.map { it.name },
            )
            assertEquals(
                setOf(LocalDateTime.of(1980, 1, 1, 0, 0)),
                (contentEntries + platformEntries).map { it.timeLocal }.toSet(),
            )
            assertTrue(built.all { it.file.fileName.toString() == "${it.sha1}.zip" })
            assertTrue(built.all { it.sha1.matches(Regex("[0-9a-f]{40}")) })
            assertTrue(built.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun zipEntries(file: java.nio.file.Path): List<java.util.zip.ZipEntry> =
        ZipFile(file.toFile()).use { zip -> zip.entries().asSequence().toList() }
}
