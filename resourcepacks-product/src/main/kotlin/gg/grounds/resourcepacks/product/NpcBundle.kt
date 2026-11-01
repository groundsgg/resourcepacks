package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepacks.catalog.GroundsNpcModels
import java.io.IOException
import java.nio.file.Path
import tools.jackson.databind.json.JsonMapper

/** The catalogue JAR binds each generated file's digest and names every rendered model part. */
internal object NpcBundle {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private val mapper = JsonMapper.builder().build()
    private const val BASE = "/gg/grounds/resourcepacks/npc/"

    data class Captured(val contribution: PackContribution, val partPaths: Set<PackPath>)

    fun partPaths(): Set<PackPath> {
        val paths = linkedSetOf<PackPath>()
        GroundsNpcModels.modelResources.forEach { (asset, resource) ->
            check(resource.startsWith(BASE) && resource.endsWith(".json")) {
                "Invalid NPC resource path"
            }
            val node = readResource(resource)
            check(node.get("asset").asString() == asset.value) {
                "NPC catalogue asset mismatch: $resource"
            }
            node.get("parts").forEach { part ->
                val model = part.get("model").asString()
                check(
                    model.matches(
                        Regex(
                            "grounds:npc_parts/[a-z0-9_]+/[a-z0-9_]+/part_[0-9]+(?:_[0-9a-f]{64})?"
                        )
                    )
                ) {
                    "Invalid NPC part: $model"
                }
                check(
                    paths.add(
                        PackPath.of("assets/grounds/models/${model.substringAfter(':')}.json")
                    )
                ) {
                    "Duplicate NPC part: $model"
                }
            }
        }
        return paths
    }

    private fun readResource(path: String) =
        GroundsNpcModels::class.java.getResourceAsStream(path)?.use { mapper.readTree(it) }
            ?: throw IOException("Missing NPC catalogue resource: $path")

    fun capture(root: Path, held: MutableMap<String, HeldSourceFile>): Captured {
        val bundle = HeldSourceFile.capture(root.resolve("bundle.json"), MAX_BYTES)
        held["npc:bundle"] = bundle
        val manifest = mapper.readTree(bundle.readBytes(MAX_BYTES))
        val files = manifest.get("files").values().map { it.asString() }
        check(files.size == files.toSet().size && files == files.sorted()) {
            "Invalid NPC bundle file list"
        }
        val digests = readResource(BASE + "pack-digests.json")
        val digestNames = digests.properties().map { it.key }.toSet()
        check(files.toSet() == digestNames) { "NPC bundle does not match catalogue files" }
        val models = manifest.get("models").values().map { it.asString() }.toSet()
        check(models == GroundsNpcModels.modelResources.keys.map { it.value }.toSet()) {
            "NPC bundle does not match catalogue models"
        }
        val parts = partPaths()
        check(
            manifest.get("partModelPaths").values().map { PackPath.of(it.asString()) }.toSet() ==
                parts
        ) {
            "NPC bundle does not match catalogue parts"
        }
        val pack = root.resolve("pack")
        val entries =
            files.map { path ->
                check(
                    path.matches(Regex("assets/grounds/[a-z0-9_./-]+")) &&
                        !path.split('/').contains("..")
                ) {
                    "Invalid NPC pack path: $path"
                }
                val source = HeldSourceFile.capture(pack.resolve(path), MAX_BYTES)
                held["npc:$path"] = source
                check(source.digests.sha256 == digests.get(path).asString()) {
                    "NPC file does not match catalogue: $path"
                }
                PackEntry.bytes(path, source.readBytes(MAX_BYTES))
            }
        check(parts.all { it.value in files }) { "NPC part is missing" }
        return Captured(
            PackContribution(
                ContributionId.of("grounds:npc-models"),
                PackFormatRange(PackSetConstants.FORMAT, PackSetConstants.FORMAT),
                entries,
                emptySet(),
                emptySet(),
                emptySet(),
            ),
            parts,
        )
    }
}
