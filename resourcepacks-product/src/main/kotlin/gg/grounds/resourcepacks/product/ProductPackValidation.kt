package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog

internal fun validatedProductPacks(packs: List<PhysicalPack>): List<PhysicalPack> {
    val orderedPacks = packs.sortedBy(PhysicalPack::order)
    ProductGraphValidator.validate(orderedPacks).also { result ->
        if (!result.isValid) throw ProductValidationException(result)
    }
    CatalogParityValidator.validate(
            GroundsAssetCatalog.catalog,
            orderedPacks
                .first { it.role == PackRole.CONTENT }
                .contributions
                .flatMap { it.entries }
                .map { it.path }
                .toSet(),
        )
        .also { result -> if (!result.isValid) throw ProductValidationException(result) }
    return orderedPacks
}
