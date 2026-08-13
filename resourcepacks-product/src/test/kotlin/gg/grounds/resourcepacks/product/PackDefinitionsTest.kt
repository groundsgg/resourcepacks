package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackFormat
import gg.grounds.resourcepack.api.VanillaPathPolicy
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackDefinitionsTest {
    @Test
    fun `product graph is the locked required two-pack stack`() {
        val packs = ProductGraph.packs

        assertEquals(
            listOf(
                ExpectedPack(
                    0,
                    PackRole.CONTENT,
                    "grounds-content",
                    UUID.fromString("44591d5b-71f5-5c2a-a5b2-d3ee7be47e53"),
                    VanillaPathPolicy.FORBID,
                ),
                ExpectedPack(
                    1,
                    PackRole.PLATFORM,
                    "grounds-platform",
                    UUID.fromString("8da7cffe-bb04-55e0-9868-7789ce5de362"),
                    VanillaPathPolicy.ALLOW_CLAIMED,
                ),
            ),
            packs.map {
                ExpectedPack(it.order, it.role, it.id, it.uuid, it.definition.policy.vanillaPaths)
            },
        )
        assertTrue(packs.all { it.required })
        assertTrue(packs.all { it.definition.format == PackFormat(88) })
        assertEquals(ContentContribution.id, packs.first().contributions.single().id)
        assertEquals(1, packs.last().contributions.size)
    }

    @Test
    fun `content contribution has no entries claims or capabilities`() {
        assertEquals(emptyList(), ContentContribution.entries)
        assertEquals(emptySet(), ContentContribution.vanillaClaims)
        assertEquals(emptySet(), ContentContribution.requires)
        assertEquals(emptySet(), ContentContribution.provides)
    }

    @Test
    fun `platform vanilla paths have same-owner exact claims`() {
        val contribution = ProductGraph.packs.last().contributions.single()
        val vanillaEntries =
            contribution.entries
                .map { it.path.toString() }
                .filter { it.startsWith("assets/minecraft/") }
                .toSet()
        val claims = contribution.vanillaClaims.map { it.path.toString() }.toSet()

        assertEquals(vanillaEntries, claims)
    }

    private data class ExpectedPack(
        val order: Int,
        val role: PackRole,
        val id: String,
        val uuid: UUID,
        val vanillaPolicy: VanillaPathPolicy,
    )
}
