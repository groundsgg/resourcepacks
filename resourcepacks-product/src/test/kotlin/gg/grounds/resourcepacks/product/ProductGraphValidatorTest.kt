package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.FileEntrySource
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormat
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackLimits
import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepack.api.VanillaPathClaim
import java.nio.file.Files
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

    @Test
    fun `validator orders equal diagnostics independently of contribution input order`() {
        val first = vanillaContribution("grounds:first")
        val second = vanillaContribution("grounds:second")
        val forward = ProductGraph.packs.first().copy(contributions = listOf(first, second))
        val reversed = ProductGraph.packs.first().copy(contributions = listOf(second, first))

        assertEquals(
            ProductGraphValidator.validate(listOf(forward, ProductGraph.packs.last())).problems,
            ProductGraphValidator.validate(listOf(reversed, ProductGraph.packs.last())).problems,
        )
    }

    @Test
    fun `validator accumulates malformed limits and source size failures without throwing`() {
        val root = Files.createTempDirectory("product-validator-missing")
        try {
            val missing = root.resolve("missing.bin")
            val contribution =
                PackContribution(
                    ContributionId.of("grounds:missing"),
                    PackFormatRange(88, 88),
                    listOf(PackEntry.file("assets/grounds/missing.bin", missing)),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                )
            val content =
                ProductGraph.packs
                    .first()
                    .copy(
                        definition =
                            ProductGraph.packs
                                .first()
                                .definition
                                .copy(
                                    policy =
                                        ProductGraph.packs
                                            .first()
                                            .definition
                                            .policy
                                            .copy(limits = PackLimits(null, null, null))
                                ),
                        contributions = listOf(contribution),
                    )

            assertEquals(
                listOf(
                    ProductProblem(ProductProblemCode.INVALID_LIMITS, "grounds-content"),
                    ProductProblem(
                        ProductProblemCode.SOURCE_SIZE_FAILURE,
                        "grounds-content",
                        "assets/grounds/missing.bin",
                        contributionIds = listOf("grounds:missing"),
                    ),
                ),
                ProductGraphValidator.validate(listOf(content, ProductGraph.packs.last())).problems,
            )
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    @Test
    fun `validator enforces locked content entry limit despite permissive configured limits`() {
        val contribution =
            PackContribution(
                ContributionId.of("grounds:too-many"),
                PackFormatRange(88, 88),
                List(50_001) { index -> PackEntry.text("assets/grounds/$index.txt", "") },
                emptySet(),
                emptySet(),
                emptySet(),
            )
        val content =
            ProductGraph.packs
                .first()
                .copy(
                    definition =
                        ProductGraph.packs
                            .first()
                            .definition
                            .copy(
                                policy =
                                    ProductGraph.packs
                                        .first()
                                        .definition
                                        .policy
                                        .copy(
                                            limits =
                                                PackLimits(
                                                    Int.MAX_VALUE,
                                                    Long.MAX_VALUE,
                                                    Long.MAX_VALUE,
                                                )
                                        )
                            ),
                    contributions = listOf(contribution),
                )

        assertEquals(
            listOf(
                ProductProblem(ProductProblemCode.INVALID_LIMITS, "grounds-content"),
                ProductProblem(ProductProblemCode.ENTRY_LIMIT_EXCEEDED, "grounds-content"),
            ),
            ProductGraphValidator.validate(listOf(content, ProductGraph.packs.last())).problems,
        )
    }

    @Test
    fun `validator reports source size overflow without wrapping`() {
        assertFailsWith<ArithmeticException> { checkedSizeAdd(Long.MAX_VALUE, 1) }
    }

    @Test
    fun `validator reports an unreadable optional icon as the generated pack png path`() {
        val root = Files.createTempDirectory("product-validator-missing-icon")
        try {
            val platform =
                ProductGraph.packs
                    .last()
                    .copy(
                        definition =
                            ProductGraph.packs
                                .last()
                                .definition
                                .copy(icon = FileEntrySource(root.resolve("missing.png")))
                    )

            assertEquals(
                listOf(
                    ProductProblem(
                        ProductProblemCode.SOURCE_SIZE_FAILURE,
                        "grounds-platform",
                        "pack.png",
                    )
                ),
                ProductGraphValidator.validate(listOf(ProductGraph.packs.first(), platform))
                    .problems,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
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
