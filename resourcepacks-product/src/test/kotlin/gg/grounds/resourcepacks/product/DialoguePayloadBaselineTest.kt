package gg.grounds.resourcepacks.product

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import tools.jackson.databind.json.JsonMapper

class DialoguePayloadBaselineTest {
    @Test
    fun `dialogue import keeps all existing payload bytes unchanged`() {
        val base = "/grounds-dialogue/grounds-26.3/"
        fun read(name: String) =
            checkNotNull(javaClass.getResourceAsStream(base + name)) {
                    "Missing generated dialogue asset: $name"
                }
                .use { it.readBytes() }

        val manifest = JsonMapper.builder().build().readTree(read("manifest.json"))
        val files = manifest.get("files")
        val futureVoicePaths =
            setOf("assets/dialogue/sounds.json", "assets/dialogue/guide/voice-bank.json") +
                (1..3).map { "assets/dialogue/sounds/guide/end%02d.ogg".format(it) }
        val existingPayloads = files.properties().filterNot { it.key in futureVoicePaths }
        assertEquals(612, existingPayloads.size)

        val aggregate = MessageDigest.getInstance("SHA-256")
        existingPayloads
            .sortedBy { it.key }
            .forEach { (path, expectedDigest) ->
                val digest = expectedDigest.asString()
                val bytes = read(path)
                assertEquals(digest, sha256(bytes), "Existing payload changed: $path")
                aggregate.update(path.toByteArray(Charsets.UTF_8))
                aggregate.update(0.toByte())
                aggregate.update(digest.toByteArray(Charsets.US_ASCII))
                aggregate.update('\n'.code.toByte())
            }
        assertEquals(
            "f7a8495d78343ddc77d2aa38faa015544a4ff6e08f57854e54990910e20c5b17",
            aggregate.digest().joinToString("") { "%02x".format(it) },
            "Existing dialogue payload set changed",
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
