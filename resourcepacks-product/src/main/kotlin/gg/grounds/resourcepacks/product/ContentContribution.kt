package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange

internal object ContentContribution {
    val id: ContributionId = ContributionId.of("grounds:content")

    fun withModels(sources: Map<String, HeldSourceFile>): PackContribution =
        PackContribution(
            id,
            PackFormatRange(PackSetConstants.FORMAT, PackSetConstants.FORMAT),
            MODEL_PACK_PATHS.map { (sourcePath, packPath) ->
                val source = sources.getValue(sourcePath)
                PackEntry.bytes(packPath, source.readBytes(source.digests.size))
            },
            emptySet(),
            emptySet(),
            emptySet(),
        )

    private val MODEL_PACK_PATHS =
        linkedMapOf(
            "models/editor/marker.json" to "assets/grounds/models/editor/marker.json",
            "models/npc_bodies/editor/guide.json" to
                "assets/grounds/models/npc_bodies/editor/guide.json",
        )

    val modelSourcePaths: Set<String> = MODEL_PACK_PATHS.keys
}
