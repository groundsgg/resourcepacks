package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class NpcCompositeGraphTest {
    @Test
    fun `in-place source change cannot reuse a previously captured catalogue digest`() {
        val path = Files.createTempFile("npc-captured-change-", ".json")
        val original = "original".toByteArray()
        Files.write(path, original)
        val modified = "modified".toByteArray()
        val mtime = Files.getLastModifiedTime(path)
        try {
            HeldSourceFile.capture(path, 1024).use { source ->
                val expected = source.digests.sha256
                Files.write(path, modified)
                Files.setLastModifiedTime(path, mtime)
                try {
                    assertFails { NpcBundle.materializeFile(source, expected) }
                } finally {
                    Files.write(path, original)
                    Files.setLastModifiedTime(path, mtime)
                }
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `extensionless items cannot replace or alias canonical json definitions`() {
        val path = "assets/grounds/items/npc_parts/townfolk/builder/part_000_${"a".repeat(64)}"
        assertFails { NpcBundle.indexItemDefinitions(mapOf(path to item(leaf(first)))) }
        assertFails {
            NpcBundle.indexItemDefinitions(
                mapOf(path to item(leaf(first)), "$path.json" to item(leaf(first)))
            )
        }
        assertEquals(
            setOf(first),
            NpcBundle.indexItemDefinitions(mapOf("$path.json" to item(leaf(first)))).keys,
        )
    }

    private val root = "grounds:npc_parts/townfolk/builder/part_002_${"c".repeat(64)}"
    private val first = "grounds:npc_parts/townfolk/builder/part_000_${"a".repeat(64)}"
    private val second = "grounds:npc_parts/townfolk/builder/part_001_${"b".repeat(64)}"
    private val owner = "grounds:npc/townfolk/builder"

    private fun native(id: String) =
        PackPath.of("assets/grounds/models/${id.substringAfter(':')}.json")

    private fun leaf(id: String) = """{"type":"minecraft:model","model":"$id"}"""

    private fun item(node: String) = """{"model":$node}""".toByteArray()

    private fun composite(vararg nodes: String) =
        """{"type":"minecraft:composite","models":[${nodes.joinToString(",")}] }"""

    private fun files() =
        mapOf(
            root to item(composite(leaf(first), leaf(second))),
            first to item(leaf(first)),
            second to item(leaf(second)),
        )

    @Test
    fun `composite root needs an item definition and preserves both native leaves`() {
        // There is intentionally no native model named after the composite root.
        NpcBundle.validatePartGraph(
            mapOf(root to owner),
            files(),
            setOf(native(first), native(second)),
        )
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() - root,
                setOf(native(first), native(second)),
            )
        }
        assertFails {
            NpcBundle.validatePartGraph(mapOf(root to owner), files(), setOf(native(first)))
        }
    }

    @Test
    fun `missing repeated and unreferenced leaves fail closed`() {
        val natives = setOf(native(first), native(second))
        assertFails { NpcBundle.validatePartGraph(mapOf(root to owner), files() - second, natives) }
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() + (root to item(composite(leaf(first), leaf(first)))),
                natives,
            )
        }
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() + (root to item(composite(leaf(first)))),
                natives,
            )
        }
    }

    @Test
    fun `swapping leaves between two catalogue owners is rejected despite full global coverage`() {
        val foreign = "grounds:npc_parts/guildfolk/forge_master/part_000_${"d".repeat(64)}"
        val foreignRoot = "grounds:npc_parts/guildfolk/forge_master/part_001_${"e".repeat(64)}"
        val roots = mapOf(root to owner, foreignRoot to "grounds:npc/guildfolk/forge_master")
        val items =
            files() +
                mapOf(
                    foreign to item(leaf(foreign)),
                    foreignRoot to item(composite(leaf(first))),
                    root to item(composite(leaf(foreign), leaf(second))),
                )
        assertFails {
            NpcBundle.validatePartGraph(
                roots,
                items,
                setOf(native(first), native(second), native(foreign)),
            )
        }
    }

    @Test
    fun `legacy native leaf item must still reference itself`() {
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() + (first to item(leaf(second))),
                setOf(native(first), native(second)),
            )
        }
    }

    @Test
    fun `nonfinite client float projective and incorrectly sized matrices fail closed`() {
        val matrices =
            listOf(
                "[1,0,0,1e100,0,1,0,0,0,0,1,0,0,0,0,1]",
                "[1,0,0,0,0,1,0,0,0,0,1,0,0,0,1,1]",
                "[1,0,0]",
                "[1,0,0,\"0\",0,1,0,0,0,0,1,0,0,0,0,1]",
            )
        for (matrix in matrices) {
            val node = """{"type":"minecraft:model","model":"$first","transformation":$matrix}"""
            assertFails {
                NpcBundle.validatePartGraph(
                    mapOf(root to owner),
                    files() + (root to item(composite(node, leaf(second)))),
                    setOf(native(first), native(second)),
                )
            }
        }
    }

    @Test
    fun `recursive composites and unused item definitions fail closed`() {
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() + (root to item(composite(composite(leaf(first)), leaf(second)))),
                setOf(native(first), native(second)),
            )
        }
        val unused = "grounds:npc_parts/townfolk/builder/part_003_${"f".repeat(64)}"
        assertFails {
            NpcBundle.validatePartGraph(
                mapOf(root to owner),
                files() + (unused to item(leaf(first))),
                setOf(native(first), native(second)),
            )
        }
    }
}
