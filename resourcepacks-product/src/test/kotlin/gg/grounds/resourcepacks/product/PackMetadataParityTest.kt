package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackDefinition
import gg.grounds.resourcepack.api.PackLimits
import gg.grounds.resourcepack.builder.ResourcePackComposer
import gg.grounds.resourcepack.builder.ZipPackWriter
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PackMetadataParityTest {
    @Test
    fun `release metadata is byte identical to upstream format 88 metadata`() {
        val definitions =
            ProductGraph.packs.map { it.definition } +
                ProductGraph.packs
                    .first()
                    .definition
                    .copy(description = "quote \" slash \\ controls \b\u000C\n\r\t unicode 🪨")
        definitions.forEach { definition ->
            val upstream = upstreamMetadata(definition)
            val product = productMetadata(definition)

            assertContentEquals(upstream, product, definition.description)
            assertEquals(upstream.size.toLong(), ProductPackMetadata.size(definition))
        }
    }

    private fun upstreamMetadata(definition: PackDefinition): ByteArray =
        metadataFromZip("upstream") { target ->
            val unbounded = definition.copy(policy = definition.policy.copy(limits = PackLimits()))
            ZipPackWriter().write(ResourcePackComposer().compose(unbounded, emptyList()), target)
        }

    private fun productMetadata(definition: PackDefinition): ByteArray =
        ByteArrayOutputStream().use { output ->
            ProductPackMetadata.write(definition, output)
            output.toByteArray()
        }

    private fun metadataFromZip(prefix: String, write: (java.nio.file.Path) -> Unit): ByteArray {
        val root = Files.createTempDirectory("pack-metadata-$prefix-")
        try {
            val target = root.resolve("$prefix.zip")
            write(target)
            return ZipFile(target.toFile()).use { zip ->
                zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
