package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsNpcModels
import gg.grounds.scene.format.AssetKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class NpcBundleTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `missing generated bundle fails closed`() {
        val root = Files.createTempDirectory("npc-missing-")
        assertFails { NpcBundle.capture(root, linkedMapOf()) }
    }

    @Test
    fun `missing generated pack file fails closed`() {
        if (GroundsNpcModels.definitions.isEmpty()) return
        val source =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
                .resolve("build/generated/npc-models")
        val root = Files.createTempDirectory("npc-missing-file-")
        Files.copy(source.resolve("bundle.json"), root.resolve("bundle.json"))
        Files.createDirectory(root.resolve("pack"))
        val held = linkedMapOf<String, HeldSourceFile>()
        try {
            assertFails { NpcBundle.capture(root, held) }
        } finally {
            held.values.forEach { it.close() }
        }
    }

    @Test
    fun `generated pack file differing from catalogue digest fails closed`() {
        if (GroundsNpcModels.definitions.isEmpty()) return
        val source =
            generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
                .resolve("build/generated/npc-models")
        val root = Files.createTempDirectory("npc-mismatch-")
        Files.copy(source.resolve("bundle.json"), root.resolve("bundle.json"))
        val first =
            mapper.readTree(Files.readAllBytes(root.resolve("bundle.json")))["files"][0].asString()
        val target = root.resolve("pack").resolve(first)
        Files.createDirectories(target.parent)
        Files.writeString(target, "different bytes")
        val held = linkedMapOf<String, HeldSourceFile>()
        try {
            val failure = assertFails { NpcBundle.capture(root, held) }
            assertTrue(failure.message.orEmpty().contains("does not match catalogue"))
        } finally {
            held.values.forEach { it.close() }
        }
    }

    @Test
    fun `licensed catalogue resources and generated pack references agree`() {
        val definitions = GroundsNpcModels.definitions
        if (definitions.isEmpty()) return
        val bodies = definitions.filterValues { it.kind == AssetKind.NPC_BODY }
        val sounds = definitions.filterValues { it.kind == AssetKind.SOUND }
        assertEquals(15, bodies.size)
        assertEquals(45, bodies.values.sumOf { it.animations.size })
        assertEquals(10, sounds.size)
        ProductGraph.secureReleaseInputs().use { secured ->
            val entries =
                secured.packs
                    .first { it.role == PackRole.CONTENT }
                    .contributions
                    .flatMap { it.entries }
                    .associateBy { it.path.value }
            fun json(path: String) =
                mapper.readTree(assertNotNull(entries[path], path).source.openStream())
            GroundsNpcModels.modelResources.forEach { (key, resource) ->
                val model =
                    GroundsNpcModels::class.java.getResourceAsStream(resource).use {
                        mapper.readTree(assertNotNull(it))
                    }
                assertEquals(key.value, model["asset"].asString())
                assertNotNull(
                    entries[
                        "assets/grounds/models/npc_bodies/${key.value.substringAfter(':')}.json"]
                )
                model["parts"].forEach { part ->
                    val id = part["model"].asString().substringAfter(':')
                    val native = json("assets/grounds/models/$id.json")
                    val item = json("assets/grounds/items/$id.json")
                    assertEquals("grounds:$id", item["model"]["model"].asString())
                    native["textures"].properties().forEach { (_, texture) ->
                        val value = texture.asString()
                        val sprite =
                            if (value.startsWith("#"))
                                native["textures"][value.substring(1)].asString()
                            else value
                        assertTrue(
                            sprite.substringAfter(':').startsWith("item/"),
                            "NPC texture must be stitched into a vanilla atlas: $sprite",
                        )
                        assertNotNull(
                            entries["assets/grounds/textures/${sprite.substringAfter(':')}.png"]
                        )
                    }
                    assertNotNull(native["textures"]["particle"])
                }
                model["sounds"].properties().forEach { (_, sound) ->
                    assertNotNull(
                        entries[
                            "assets/grounds/sounds/${sound["key"].asString().substringAfter(':')}.ogg"]
                    )
                }
            }
            val events = json("assets/grounds/sounds.json")
            sounds.keys.forEach { assertTrue(events.has(it.value.substringAfter(':'))) }
        }
    }
}
