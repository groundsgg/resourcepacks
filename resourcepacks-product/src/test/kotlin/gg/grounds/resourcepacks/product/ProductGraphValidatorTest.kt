package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormat
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackLimits
import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepack.api.VanillaPathClaim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductGraphValidatorTest {
    @Test
    fun `locked product graph validates without problems`() {
        val result = ProductGraphValidator.validate(ProductGraph.packs)

        assertTrue(result.isValid)
        assertEquals(emptyList(), result.problems)
    }

    @Test
    fun `validator accumulates deterministic distinct identity format and limits problems`() {
        val content = ProductGraph.packs.first()
        val invalidPlatform =
            content.copy(
                order = 1,
                role = PackRole.PLATFORM,
                id = "grounds-platform",
                uuid = PackSetConstants.platformUuid,
                definition =
                    content.definition.copy(
                        format = PackFormat(87),
                        policy = content.definition.policy.copy(limits = PackLimits(1, 2, 3)),
                    ),
            )

        assertEquals(
            listOf(
                ProductProblem(ProductProblemCode.DUPLICATE_ROLE, detail = "PLATFORM"),
                ProductProblem(ProductProblemCode.DUPLICATE_ORDER, detail = "1"),
                ProductProblem(ProductProblemCode.DUPLICATE_ID, pack = "grounds-platform"),
                ProductProblem(
                    ProductProblemCode.DUPLICATE_UUID,
                    detail = "8da7cffe-bb04-55e0-9868-7789ce5de362",
                ),
                ProductProblem(ProductProblemCode.INVALID_FORMAT, pack = "grounds-platform"),
                ProductProblem(ProductProblemCode.INVALID_LIMITS, pack = "grounds-platform"),
            ),
            ProductGraphValidator.validate(listOf(ProductGraph.packs.last(), invalidPlatform))
                .problems,
        )
    }

    @Test
    fun `validator rejects duplicate cross-pack paths regardless of bytes`() {
        val equal = contribution("grounds:equal", "same")
        val unequal = contribution("grounds:unequal", "different")
        val content = ProductGraph.packs.first().copy(contributions = listOf(equal))
        val platform = ProductGraph.packs.last().copy(contributions = listOf(unequal))

        assertEquals(
            listOf(
                ProductProblem(
                    ProductProblemCode.CROSS_PACK_PATH,
                    "grounds-content,grounds-platform",
                    "assets/grounds/test.txt",
                )
            ),
            ProductGraphValidator.validate(listOf(content, platform)).problems,
        )
    }

    @Test
    fun `validator rejects content vanilla entries and claims`() {
        val forbidden =
            PackContribution(
                ContributionId.of("grounds:forbidden"),
                PackFormatRange(88, 88),
                listOf(PackEntry.text("assets/minecraft/textures/test.png", "x")),
                setOf(VanillaPathClaim(PackPath.of("assets/minecraft/textures/test.png"))),
                emptySet(),
                emptySet(),
            )
        val content = ProductGraph.packs.first().copy(contributions = listOf(forbidden))

        assertEquals(
            listOf(
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_PATH,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:forbidden"),
                ),
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_CLAIM,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:forbidden"),
                ),
            ),
            ProductGraphValidator.validate(listOf(content, ProductGraph.packs.last())).problems,
        )
    }

    @Test
    fun `validator rejects a stack member whose locked identity is changed without creating a duplicate`() {
        val changed = ProductGraph.packs.first().copy(id = "renamed-content")

        assertEquals(
            listOf(
                ProductProblem(
                    ProductProblemCode.LOCKED_PACK_MISMATCH,
                    "renamed-content",
                    detail = "id",
                )
            ),
            ProductGraphValidator.validate(listOf(changed, ProductGraph.packs.last())).problems,
        )
    }

    @Test
    fun `validation result snapshots problems against Kotlin and JVM mutable casts`() {
        val source = mutableListOf(ProductProblem(ProductProblemCode.PACK_COUNT))
        val result = ProductValidationResult(source)
        source += ProductProblem(ProductProblemCode.DUPLICATE_ID)

        assertEquals(listOf(ProductProblem(ProductProblemCode.PACK_COUNT)), result.problems)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.problems as MutableList<ProductProblem>) +=
                ProductProblem(ProductProblemCode.DUPLICATE_ID)
        }
    }

    @Test
    fun `locked stack reports every wrong field in deterministic order`() {
        val changed =
            ProductGraph.packs
                .first()
                .copy(
                    order = 9,
                    id = "wrong-content",
                    uuid = PackSetConstants.platformUuid,
                    required = false,
                )

        assertEquals(
            listOf(
                ProductProblem(
                    ProductProblemCode.DUPLICATE_UUID,
                    detail = "8da7cffe-bb04-55e0-9868-7789ce5de362",
                ),
                ProductProblem(
                    ProductProblemCode.LOCKED_PACK_MISMATCH,
                    "wrong-content",
                    detail = "id",
                ),
                ProductProblem(
                    ProductProblemCode.LOCKED_PACK_MISMATCH,
                    "wrong-content",
                    detail = "order",
                ),
                ProductProblem(
                    ProductProblemCode.LOCKED_PACK_MISMATCH,
                    "wrong-content",
                    detail = "required",
                ),
                ProductProblem(
                    ProductProblemCode.LOCKED_PACK_MISMATCH,
                    "wrong-content",
                    detail = "uuid",
                ),
            ),
            ProductGraphValidator.validate(listOf(changed, ProductGraph.packs.last())).problems,
        )
    }

    @Test
    fun `content vanilla violations retain every contributing owner`() {
        val first = vanillaContribution("grounds:first")
        val second = vanillaContribution("grounds:second")
        val content = ProductGraph.packs.first().copy(contributions = listOf(first, second))

        assertEquals(
            listOf(
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_PATH,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:first"),
                ),
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_PATH,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:second"),
                ),
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_CLAIM,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:first"),
                ),
                ProductProblem(
                    ProductProblemCode.CONTENT_VANILLA_CLAIM,
                    "grounds-content",
                    "assets/minecraft/textures/test.png",
                    contributionIds = listOf("grounds:second"),
                ),
            ),
            ProductGraphValidator.validate(listOf(content, ProductGraph.packs.last())).problems,
        )
    }

    private fun contribution(id: String, bytes: String) =
        PackContribution(
            ContributionId.of(id),
            PackFormatRange(88, 88),
            listOf(PackEntry.text("assets/grounds/test.txt", bytes)),
            emptySet(),
            emptySet(),
            emptySet(),
        )

    private fun vanillaContribution(id: String) =
        PackContribution(
            ContributionId.of(id),
            PackFormatRange(88, 88),
            listOf(PackEntry.text("assets/minecraft/textures/test.png", "x")),
            setOf(VanillaPathClaim(PackPath.of("assets/minecraft/textures/test.png"))),
            emptySet(),
            emptySet(),
        )
}
