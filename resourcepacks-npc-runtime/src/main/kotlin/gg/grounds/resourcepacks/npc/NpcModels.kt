package gg.grounds.resourcepacks.npc

import com.google.gson.JsonParser
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.*

object NpcModels {
    fun load(assets: AssetCatalog): Map<AssetKey, NpcModel> {
        fun read(path: String, max: Int): String? =
            GroundsAssetCatalog::class.java.getResourceAsStream(path)?.use { stream ->
                val bytes = stream.readNBytes(max + 1)
                require(bytes.size <= max) { "NPC resource exceeds size limit: $path" }
                bytes.toString(Charsets.UTF_8)
            }
        val index = read("/gg/grounds/resourcepacks/npc/index.json", 64 * 1024) ?: return emptyMap()
        val root = JsonParser.parseString(index).asJsonObject
        require(root.get("schemaVersion").asInt == 1)
        val entries = root.getAsJsonArray("models")
        require(entries.size() <= 256)
        val models = linkedMapOf<AssetKey, NpcModel>()
        entries.forEach { value ->
            val entry = value.asJsonObject
            val key = AssetKey(entry.get("asset").asString)
            val path = entry.get("resource").asString
            require(
                path.startsWith("/gg/grounds/resourcepacks/npc/") &&
                    path.endsWith(".json") &&
                    ".." !in path &&
                    '\\' !in path
            )
            val model =
                NpcModel.parse(
                    requireNotNull(read(path, 2 * 1024 * 1024)) {
                        "Missing compiled NPC model $path"
                    }
                )
            require(model.asset == key && models.put(key, model) == null)
        }
        models.forEach { (key, model) ->
            val definition =
                requireNotNull(assets.assets[key]) { "NPC model absent from exact catalogue: $key" }
            require(
                definition.kind == AssetKind.NPC_BODY &&
                    definition.defaultBounds == model.bounds &&
                    definition.animations == model.animations.keys.map(::LocalId).toSet()
            ) {
                "NPC model/catalogue contract mismatch: $key"
            }
            require(
                model.sounds.values.all { assets.assets[AssetKey(it.key)]?.kind == AssetKind.SOUND }
            ) {
                "NPC sound missing from catalogue"
            }
        }
        return models.toMap()
    }
}
