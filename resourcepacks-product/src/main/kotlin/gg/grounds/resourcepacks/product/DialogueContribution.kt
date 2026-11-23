package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.ContributionId
import gg.grounds.resourcepack.api.PackContribution
import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackFormatRange
import java.security.MessageDigest
import tools.jackson.databind.json.JsonMapper

/** Frozen library assets and the Grounds palette form one independently pinned contribution. */
internal object DialogueContribution {
    private const val BASE = "/grounds-dialogue/grounds-26.3/"
    private val voicePaths =
        setOf("assets/grounds/sounds.json") +
            listOf("bwa", "li", "bup", "wee", "mwa").map {
                "assets/grounds/sounds/dialogue/guide/$it.ogg"
            }
    private val mapper = JsonMapper.builder().build()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(BASE + name)) {
                "Missing dialogue resource: $name"
            }
            .use { it.readBytes() }

    fun create(): PackContribution {
        val manifest = resource("manifest.json")
        val metadata = mapper.readTree(manifest)
        check(metadata.get("profile").asString() == "grounds-26.3")
        val entries =
            metadata
                .get("files")
                .properties()
                .sortedBy { it.key }
                .map { (path, hash) ->
                    check(
                        (path.startsWith("assets/dialogue/") || path in voicePaths) &&
                            !path.contains("..")
                    )
                    val bytes = resource(path)
                    val digest =
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        }
                    check(digest == hash.asString()) { "Dialogue asset digest mismatch: $path" }
                    PackEntry.bytes(path, bytes)
                }
        return PackContribution(
            ContributionId.of("grounds:dialogue"),
            PackFormatRange(PackSetConstants.FORMAT, PackSetConstants.FORMAT),
            entries +
                listOf(
                    PackEntry.bytes("assets/grounds/dialogue/manifest.json", manifest),
                    PackEntry.bytes("assets/grounds/dialogue/theme.json", resource("theme.json")),
                    PackEntry.bytes(
                        "assets/grounds/licenses/dialogue.txt",
                        checkNotNull(javaClass.getResourceAsStream("/grounds-dialogue/LICENSE"))
                            .use { it.readBytes() },
                    ),
                    PackEntry.bytes(
                        "assets/grounds/licenses/guide-voice-provenance.txt",
                        checkNotNull(
                                javaClass.getResourceAsStream(
                                    "/grounds-dialogue/GUIDE-VOICE-PROVENANCE.txt"
                                )
                            )
                            .use { it.readBytes() },
                    ),
                    PackEntry.bytes(
                        "assets/grounds/licenses/dialogue-notice.txt",
                        checkNotNull(javaClass.getResourceAsStream("/grounds-dialogue/NOTICE"))
                            .use { it.readBytes() },
                    ),
                ),
            emptySet(),
            emptySet(),
            emptySet(),
        )
    }
}
