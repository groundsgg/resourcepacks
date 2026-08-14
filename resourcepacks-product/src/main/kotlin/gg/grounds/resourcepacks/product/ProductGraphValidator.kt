package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackEntrySource
import java.io.IOException
import java.util.Collections

internal class ProductProblem(
    val code: ProductProblemCode,
    val pack: String? = null,
    val path: String? = null,
    val detail: String? = null,
    contributionIds: Collection<String> = emptyList(),
) {
    val contributionIds: List<String> = Collections.unmodifiableList(contributionIds.sorted())

    override fun equals(other: Any?): Boolean =
        other is ProductProblem &&
            code == other.code &&
            pack == other.pack &&
            path == other.path &&
            detail == other.detail &&
            contributionIds == other.contributionIds

    override fun hashCode(): Int = listOf(code, pack, path, detail, contributionIds).hashCode()

    override fun toString(): String =
        "ProductProblem(code=$code, pack=$pack, path=$path, detail=$detail, contributionIds=$contributionIds)"
}

internal enum class ProductProblemCode {
    PACK_COUNT,
    DUPLICATE_ROLE,
    DUPLICATE_ORDER,
    DUPLICATE_ID,
    DUPLICATE_UUID,
    LOCKED_PACK_MISMATCH,
    INVALID_FORMAT,
    INVALID_LIMITS,
    ENTRY_LIMIT_EXCEEDED,
    UNCOMPRESSED_SIZE_LIMIT_EXCEEDED,
    SIZE_LIMIT_EXCEEDED,
    SOURCE_SIZE_FAILURE,
    CROSS_PACK_PATH,
    CONTENT_VANILLA_PATH,
    CONTENT_VANILLA_CLAIM,
    CATALOG_MISSING_CONTENT,
    CATALOG_EXTRA_CONTENT,
    CATALOG_WRONG_KIND,
}

internal class ProductValidationResult(problems: Collection<ProductProblem>) {
    val problems: List<ProductProblem> = Collections.unmodifiableList(problems.toList())

    val isValid: Boolean
        get() = problems.isEmpty()
}

internal object ProductGraphValidator {
    fun validate(packs: List<PhysicalPack>): ProductValidationResult {
        val problems = mutableListOf<ProductProblem>()
        if (packs.size != 2)
            problems +=
                ProductProblem(ProductProblemCode.PACK_COUNT, detail = packs.size.toString())
        duplicateValues(packs, PhysicalPack::role).forEach {
            problems += ProductProblem(ProductProblemCode.DUPLICATE_ROLE, detail = it.name)
        }
        duplicateValues(packs, PhysicalPack::order).forEach {
            problems += ProductProblem(ProductProblemCode.DUPLICATE_ORDER, detail = it.toString())
        }
        duplicateValues(packs, PhysicalPack::id).forEach {
            problems += ProductProblem(ProductProblemCode.DUPLICATE_ID, pack = it)
        }
        duplicateValues(packs, PhysicalPack::uuid).forEach {
            problems += ProductProblem(ProductProblemCode.DUPLICATE_UUID, detail = it.toString())
        }

        packs.forEach { pack ->
            lockedPackProblems(pack).let(problems::addAll)
            if (
                pack.definition.format.format != PackSetConstants.FORMAT ||
                    pack.definition.format.range.minInclusive != PackSetConstants.FORMAT ||
                    pack.definition.format.range.maxInclusive != PackSetConstants.FORMAT
            )
                problems += ProductProblem(ProductProblemCode.INVALID_FORMAT, pack = pack.id)
            if (pack.definition.policy.limits != expectedLimits(pack.role)) {
                problems += ProductProblem(ProductProblemCode.INVALID_LIMITS, pack = pack.id)
            }
            val entries = pack.contributions.flatMap { it.entries }
            val enforcementLimits = expectedLimits(pack.role)
            val physicalEntryCount =
                entries.size.toLong() + 1L + if (pack.definition.icon == null) 0L else 1L
            if (physicalEntryCount > requireNotNull(enforcementLimits.maxEntries).toLong()) {
                problems += ProductProblem(ProductProblemCode.ENTRY_LIMIT_EXCEEDED, pack = pack.id)
            }
            val totalSize = sourceSize(pack, problems)
            if (totalSize.overflowed) {
                problems += ProductProblem(ProductProblemCode.SIZE_LIMIT_EXCEEDED, pack = pack.id)
            } else if (totalSize.bytes > requireNotNull(enforcementLimits.maxUncompressedBytes)) {
                problems +=
                    ProductProblem(
                        ProductProblemCode.UNCOMPRESSED_SIZE_LIMIT_EXCEEDED,
                        pack = pack.id,
                    )
            }
            if (pack.role == PackRole.CONTENT) {
                pack.contributions
                    .flatMap { contribution ->
                        contribution.entries
                            .map { contribution.id.toString() to it.path.toString() }
                            .filter { (_, path) -> isVanillaPath(path) }
                    }
                    .forEach {
                        problems +=
                            ProductProblem(
                                ProductProblemCode.CONTENT_VANILLA_PATH,
                                pack.id,
                                it.second,
                                contributionIds = listOf(it.first),
                            )
                    }
                pack.contributions
                    .flatMap { contribution ->
                        contribution.vanillaClaims.map {
                            contribution.id.toString() to it.path.toString()
                        }
                    }
                    .forEach {
                        problems +=
                            ProductProblem(
                                ProductProblemCode.CONTENT_VANILLA_CLAIM,
                                pack.id,
                                it.second,
                                contributionIds = listOf(it.first),
                            )
                    }
            }
        }
        packs
            .flatMap { pack ->
                pack.contributions.flatMap { contribution ->
                    contribution.entries.map { pack.id to it.path.toString() }
                }
            }
            .groupBy({ it.second }, { it.first })
            .filterValues { it.distinct().size > 1 }
            .forEach { (path, owners) ->
                problems +=
                    ProductProblem(
                        ProductProblemCode.CROSS_PACK_PATH,
                        owners.sorted().joinToString(","),
                        path,
                    )
            }
        return ProductValidationResult(problems.distinct().sortedWith(PROBLEM_ORDER))
    }

    private fun expectedLimits(role: PackRole) =
        when (role) {
            PackRole.CONTENT -> PackSetConstants.contentLimits
            PackRole.PLATFORM -> PackSetConstants.platformLimits
        }

    private fun sourceSize(pack: PhysicalPack, problems: MutableList<ProductProblem>): TotalSize {
        var total =
            try {
                ProductPackMetadata.size(pack.definition)
            } catch (_: ArithmeticException) {
                return TotalSize(Long.MAX_VALUE, overflowed = true)
            }
        pack.definition.icon?.let { icon ->
            val size = sourceSize(pack, null, "pack.png", icon, problems) ?: return@let
            try {
                total = checkedSizeAdd(total, size)
            } catch (_: ArithmeticException) {
                return TotalSize(Long.MAX_VALUE, overflowed = true)
            }
        }
        pack.contributions.forEach { contribution ->
            contribution.entries.forEach { entry ->
                val size =
                    sourceSize(
                        pack,
                        contribution.id.toString(),
                        entry.path.toString(),
                        entry.source,
                        problems,
                    ) ?: return@forEach
                try {
                    total = checkedSizeAdd(total, size)
                } catch (_: ArithmeticException) {
                    return TotalSize(Long.MAX_VALUE, overflowed = true)
                }
            }
        }
        return TotalSize(total, overflowed = false)
    }

    private fun sourceSize(
        pack: PhysicalPack,
        contributionId: String?,
        path: String,
        source: PackEntrySource,
        problems: MutableList<ProductProblem>,
    ): Long? =
        try {
            source.size()
        } catch (_: IOException) {
            problems += sourceSizeFailure(pack, contributionId, path)
            null
        } catch (_: SecurityException) {
            problems += sourceSizeFailure(pack, contributionId, path)
            null
        } catch (_: ArithmeticException) {
            problems += sourceSizeFailure(pack, contributionId, path)
            null
        }

    private fun sourceSizeFailure(pack: PhysicalPack, contributionId: String?, path: String) =
        ProductProblem(
            ProductProblemCode.SOURCE_SIZE_FAILURE,
            pack.id,
            path,
            contributionIds = listOfNotNull(contributionId),
        )

    private fun lockedPackProblems(pack: PhysicalPack): List<ProductProblem> {
        val expected =
            when (pack.role) {
                PackRole.CONTENT -> LockedPack(0, "grounds-content", PackSetConstants.contentUuid)
                PackRole.PLATFORM ->
                    LockedPack(1, "grounds-platform", PackSetConstants.platformUuid)
            }
        return buildList {
                if (pack.order != expected.order) add("order")
                if (pack.id != expected.id) add("id")
                if (pack.uuid != expected.uuid) add("uuid")
                if (!pack.required) add("required")
            }
            .map { ProductProblem(ProductProblemCode.LOCKED_PACK_MISMATCH, pack.id, detail = it) }
    }

    private data class LockedPack(val order: Int, val id: String, val uuid: java.util.UUID)

    private fun isVanillaPath(path: String): Boolean = path.startsWith("assets/minecraft/")

    private fun <T : Comparable<T>> duplicateValues(
        packs: List<PhysicalPack>,
        selector: (PhysicalPack) -> T,
    ): List<T> = packs.groupBy(selector).filterValues { it.size > 1 }.keys.sorted()

    private val PROBLEM_ORDER =
        compareBy<ProductProblem>(
            { it.code.ordinal },
            { it.pack.orEmpty() },
            { it.path.orEmpty() },
            { it.detail.orEmpty() },
            { it.contributionIds.joinToString("\u0000") },
        )

    private data class TotalSize(val bytes: Long, val overflowed: Boolean)
}

internal fun checkedSizeAdd(total: Long, next: Long): Long = Math.addExact(total, next)
