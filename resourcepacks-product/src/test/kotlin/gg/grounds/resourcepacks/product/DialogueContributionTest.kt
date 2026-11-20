package gg.grounds.resourcepacks.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.json.JsonMapper

class DialogueContributionTest {
    @Test
    fun `dialogue bundle declares verified assets and Grounds palette`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)).use { it.readBytes() }
        val mapper = JsonMapper.builder().build()
        val manifest = mapper.readTree(read("manifest.json"))
        val theme = mapper.readTree(read("theme.json"))
        assertEquals("grounds-26.3", manifest.get("profile").asString())
        assertEquals(manifest.get("pin").asString(), theme.get("assetPin").asString())
        assertEquals("F3F0EA", theme.get("text").asString())
        assertEquals("C1B9AA", theme.get("secondary").asString())
        assertEquals("E7B84B", theme.get("accent").asString())
        assertTrue(manifest.get("files").has("assets/dialogue/font-source/font-license.txt"))
        DialogueContribution.create()
    }
}
