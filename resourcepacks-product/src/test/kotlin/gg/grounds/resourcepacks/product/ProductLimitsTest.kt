package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ByteArrayEntrySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProductLimitsTest {
    @Test
    fun `physical pack limits match the product specification`() {
        assertEquals(50_000, ProductGraph.packs[0].definition.policy.limits.maxEntries)
        assertEquals(
            512L * 1024 * 1024,
            ProductGraph.packs[0].definition.policy.limits.maxUncompressedBytes,
        )
        assertEquals(
            128L * 1024 * 1024,
            ProductGraph.packs[0].definition.policy.limits.maxArtifactBytes,
        )
        assertEquals(4_096, ProductGraph.packs[1].definition.policy.limits.maxEntries)
        assertEquals(
            64L * 1024 * 1024,
            ProductGraph.packs[1].definition.policy.limits.maxUncompressedBytes,
        )
        assertEquals(
            16L * 1024 * 1024,
            ProductGraph.packs[1].definition.policy.limits.maxArtifactBytes,
        )
    }

    @Test
    fun `platform contribution stays within its configured physical limits`() {
        val platform = ProductGraph.packs.last()
        val entries = platform.contributions.flatMap { it.entries }
        val bytes = entries.sumOf { it.source.size() }
        val limits = platform.definition.policy.limits

        assertTrue(entries.size <= requireNotNull(limits.maxEntries))
        assertTrue(bytes <= requireNotNull(limits.maxUncompressedBytes))
        assertTrue(
            ByteArrayEntrySource(ByteArray(0)).size() <= requireNotNull(limits.maxArtifactBytes)
        )
    }
}
