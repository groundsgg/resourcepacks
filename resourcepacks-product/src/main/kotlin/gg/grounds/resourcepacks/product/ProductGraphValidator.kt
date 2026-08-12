package gg.grounds.resourcepacks.product

internal data class ProductProblem(
    val code: ProductProblemCode,
    val pack: String? = null,
    val path: String? = null,
    val detail: String? = null,
)

internal enum class ProductProblemCode {
    PACK_COUNT,
    DUPLICATE_ROLE,
    DUPLICATE_ORDER,
    DUPLICATE_ID,
    DUPLICATE_UUID,
    LOCKED_PACK_MISMATCH,
    INVALID_FORMAT,
    INVALID_LIMITS,
    CROSS_PACK_PATH,
    CONTENT_VANILLA_PATH,
    CONTENT_VANILLA_CLAIM,
}

internal data class ProductValidationResult(val problems: List<ProductProblem>) {
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
            lockedPackProblem(pack)?.let(problems::add)
            if (
                pack.definition.format.format != PackSetConstants.FORMAT ||
                    pack.definition.format.range.minInclusive != PackSetConstants.FORMAT ||
                    pack.definition.format.range.maxInclusive != PackSetConstants.FORMAT
            )
                problems += ProductProblem(ProductProblemCode.INVALID_FORMAT, pack = pack.id)
            if (pack.definition.policy.limits != expectedLimits(pack.role)) {
                problems += ProductProblem(ProductProblemCode.INVALID_LIMITS, pack = pack.id)
            }
            if (pack.role == PackRole.CONTENT) {
                pack.contributions
                    .flatMap { it.entries }
                    .map { it.path.toString() }
                    .filter(::isVanillaPath)
                    .forEach {
                        problems +=
                            ProductProblem(ProductProblemCode.CONTENT_VANILLA_PATH, pack.id, it)
                    }
                pack.contributions
                    .flatMap { it.vanillaClaims }
                    .map { it.path.toString() }
                    .forEach {
                        problems +=
                            ProductProblem(ProductProblemCode.CONTENT_VANILLA_CLAIM, pack.id, it)
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

    private fun lockedPackProblem(pack: PhysicalPack): ProductProblem? {
        val expected =
            when (pack.role) {
                PackRole.CONTENT -> LockedPack(0, "grounds-content", PackSetConstants.contentUuid)
                PackRole.PLATFORM ->
                    LockedPack(1, "grounds-platform", PackSetConstants.platformUuid)
            }
        val mismatch =
            when {
                pack.order != expected.order -> "order"
                pack.id != expected.id -> "id"
                pack.uuid != expected.uuid -> "uuid"
                !pack.required -> "required"
                else -> return null
            }
        return ProductProblem(ProductProblemCode.LOCKED_PACK_MISMATCH, pack.id, detail = mismatch)
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
        )
}
