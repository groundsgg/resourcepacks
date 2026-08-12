package gg.grounds.resourcepacks.product

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PackDeterminismTest {
    @Test
    fun `fresh builds produce byte identical physical packs`() {
        val firstRoot = Files.createTempDirectory("pack-determinism-first")
        val secondRoot = Files.createTempDirectory("pack-determinism-second")
        try {
            val first = PackComposer.build(ProductGraph, firstRoot)
            val second = PackComposer.build(ProductGraph, secondRoot)

            first.zip(second).forEach { (left, right) ->
                assertContentEquals(Files.readAllBytes(left.file), Files.readAllBytes(right.file))
                assertEquals(left.sha1, right.sha1)
                assertEquals(left.sha256, right.sha256)
                assertEquals(left.size, right.size)
            }
        } finally {
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }
}
