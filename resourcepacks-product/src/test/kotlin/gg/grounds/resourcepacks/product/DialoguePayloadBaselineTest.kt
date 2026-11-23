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
            setOf("assets/grounds/sounds.json") +
                (listOf("bwa", "li", "bup", "wee", "mwa", "hello01", "hello02", "bye01", "bye02") +
                        (6..10).map { "v%02d".format(it) })
                    .map { "assets/grounds/sounds/dialogue/guide/$it.ogg" }
        val existingPayloads = files.properties().filterNot { it.key in futureVoicePaths }
        assertEquals(598, existingPayloads.size)

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
            "82ee70a5c6cb2ea949b4e42e009c5b4b933de191988ade98c78a8e1a9e6d0990",
            aggregate.digest().joinToString("") { "%02x".format(it) },
            "Existing dialogue payload set changed",
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
