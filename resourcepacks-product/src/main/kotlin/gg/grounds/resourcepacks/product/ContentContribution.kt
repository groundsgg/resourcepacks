package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackFormatRange

internal val ContentContribution =
    PackContribution(
        ContributionId.of("grounds:content"),
        PackFormatRange(PackSetConstants.FORMAT, PackSetConstants.FORMAT),
        emptyList(),
        emptySet(),
        emptySet(),
        emptySet(),
    )
