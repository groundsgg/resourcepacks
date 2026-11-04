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

    private val partId =
        Regex("grounds:npc_parts/[a-z0-9_]+/[a-z0-9_]+/part_[0-9]+(?:_[0-9a-f]{64})?")

    private fun partOwners(): Map<String, String> {
        val owners = linkedMapOf<String, String>()
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
                check(partId.matches(model)) { "Invalid NPC part: $model" }
                check(ownerOf(model) == asset.value) { "NPC part belongs to another asset: $model" }
                check(owners.put(model, asset.value) == null) { "Duplicate NPC part: $model" }
            }
        }
        return owners
    }

    private fun ownerOf(model: String): String =
        "grounds:npc/" + model.removePrefix("grounds:npc_parts/").substringBeforeLast('/')

    fun partPaths(): Set<PackPath> {
        val files = readResource(BASE + "pack-digests.json").properties().map { it.key }.toSet()
        partOwners().keys.forEach { model ->
            check("assets/grounds/items/${model.substringAfter(':')}.json" in files) {
                "NPC renderer item is missing: $model"
            }
        }
        return files
            .filter { it.startsWith("assets/grounds/models/npc_parts/") }
            .map { path ->
                check(
                    partId.matches(
                        "grounds:" +
                            path.removePrefix("assets/grounds/models/").removeSuffix(".json")
                    )
                ) {
                    "Invalid native NPC part: $path"
                }
                PackPath.of(path)
            }
            .toSet()
    }

    /** Root items can be composites; every unchanged native leaf stays owned and digest-bound. */
    internal fun validatePartGraph(
        owners: Map<String, String>,
        itemContents: Map<String, ByteArray>,
        nativePaths: Set<PackPath>,
    ) {
        val natives =
            nativePaths
                .map {
                    "grounds:" +
                        it.value.removePrefix("assets/grounds/models/").removeSuffix(".json")
                }
                .toSet()
        check(natives.all(partId::matches)) { "Invalid native NPC path" }
        check(itemContents.keys == natives + owners.keys) {
            "Missing or unused NPC item definition"
        }
        val items = itemContents.mapValues { (_, bytes) -> mapper.readTree(bytes).get("model") }
        natives.forEach { id ->
            val node = checkNotNull(items[id])
            check(
                node.get("type").asString() == "minecraft:model" &&
                    node.get("model").asString() == id &&
                    !node.has("transformation")
            ) {
                "Native NPC leaf item must reference itself: $id"
            }
        }
        val visited = mutableSetOf<String>()
        owners.forEach { (root, owner) ->
            check(partId.matches(root) && ownerOf(root) == owner) {
                "Invalid NPC root owner: $root"
            }
            val node = checkNotNull(items[root])
            val children =
                when (node.get("type").asString()) {
                    "minecraft:model" -> listOf(node)
                    "minecraft:composite" -> {
                        check(!node.has("transformation")) { "Unexpected composite root transform" }
                        val models = checkNotNull(node.get("models"))
                        check(models.isArray && models.size() in 1..1024) {
                            "Invalid NPC composite size"
                        }
                        models.toList()
                    }
                    else -> error("Unsupported NPC item model type")
                }
            children.forEach { child ->
                check(child.get("type").asString() == "minecraft:model") {
                    "NPC composites must be shallow"
                }
                val id = child.get("model").asString()
                check(id in natives && ownerOf(id) == owner && visited.add(id)) {
                    "Missing, foreign or duplicated native NPC leaf: $id"
                }
                child.get("transformation")?.let { matrix ->
                    check(matrix.isArray && matrix.size() == 16) { "Invalid NPC matrix size" }
                    val values =
                        FloatArray(16) { index ->
                            val value = matrix[index]
                            check(value.isNumber) { "NPC matrix component is not numeric" }
                            value.asDouble().toFloat().also {
                                check(it.isFinite()) { "Nonfinite client NPC matrix" }
                            }
                        }
                    check(
                        values[12] == 0f && values[13] == 0f && values[14] == 0f && values[15] == 1f
                    ) {
                        "NPC matrix is not affine"
                    }
                }
            }
        }
        check(visited == natives) { "Unreferenced native NPC geometry" }
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
        validatePartGraph(
            partOwners(),
            files
                .filter { it.startsWith("assets/grounds/items/npc_parts/") }
                .associate { path ->
                    "grounds:" + path.removePrefix("assets/grounds/items/").removeSuffix(".json") to
                        held.getValue("npc:$path").readBytes(MAX_BYTES)
                },
            parts,
        )
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
