package gg.grounds.resourcepacks.npc

import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Vec3
import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NpcPreparedClipTest {
    @Test
    fun `constant animated fifty part skeleton stays below twenty KB per pose`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean is com.sun.management.ThreadMXBean)
        val allocation = bean as com.sun.management.ThreadMXBean
        assumeTrue(allocation.isThreadAllocatedMemoryEnabled)
        val bones = (0 until 12).map {
            NpcBone("bone-$it", if (it == 0) null else "bone-${it - 1}", ZERO, ZERO)
        }
        val tracks = bones.associate { bone ->
            bone.id to mapOf(
                "position" to listOf(NpcKeyframe(0.3, ZERO, "linear")),
                "rotation" to listOf(NpcKeyframe(0.3, ZERO, "linear")),
                "scale" to listOf(NpcKeyframe(0.3, ONE, "linear")),
            )
        }
        val model = NpcModel(
            AssetKey("test:constant-allocation"), LocalBounds(ZERO, ONE), bones,
            (0 until 50).map {
                NpcPart("bone-${it % 12}", "test:part-$it", Vec3(0.0, it / 50.0, 0.0), ZERO, ZERO)
            },
            mapOf("idle" to NpcClip("loop", 2.0, tracks)), emptyMap(),
        )
        repeat(2000) { model.itemDisplayPose("idle", it / 1000.0, ZERO) }
        val id = Thread.currentThread().threadId()
        val before = allocation.getThreadAllocatedBytes(id)
        var sampled = 0.0
        repeat(1000) { sampled += model.itemDisplayPose("idle", it / 1000.0, ZERO).last().values[7] }
        val perPose = (allocation.getThreadAllocatedBytes(id) - before) / 1000
        assertEquals(980.0, sampled, 1e-8)
        assertTrue(perPose < 20_000, "Allocated $perPose bytes per constant fifty-part pose")
    }

    @Test
    fun `constant head channels preserve look order and descendants across seeks and owned results`() {
        val model = NpcModel(
            AssetKey("test:constant-head"), LocalBounds(ZERO, ONE),
            listOf(
                NpcBone("body", null, ZERO, ZERO),
                NpcBone("head", "body", Vec3(0.0, 1.0, 0.0), ZERO),
                NpcBone("hat", "head", Vec3(1.0, 1.0, 0.0), ZERO),
            ),
            listOf(
                NpcPart("body", "test:body", ZERO, ZERO, ZERO),
                NpcPart("head", "test:head", Vec3(0.0, 1.0, 0.0), ZERO, ZERO),
                NpcPart("hat", "test:hat", Vec3(1.0, 1.0, 0.0), ZERO, ZERO),
            ),
            mapOf("idle" to NpcClip("loop", 2.0, mapOf(
                "body" to mapOf("position" to listOf(NpcKeyframe(0.2, Vec3(2.0, 0.0, 0.0), "linear"))),
                "head" to mapOf(
                    "rotation" to listOf(NpcKeyframe(0.4, Vec3(0.0, 0.0, 90.0), "step")),
                    "scale" to listOf(NpcKeyframe(0.4, Vec3(2.0, 1.0, 1.0), "linear")),
                ),
            ))), emptyMap(),
        )
        for (time in listOf(0.0, 0.5, 2.2, 0.1, 20.3, -0.1)) {
            val original = model.pose("idle", time)
            point(Vec3(2.0, 0.0, 0.0), original[0])
            point(Vec3(2.0, 1.0, 0.0), original[1])
            point(Vec3(2.0, 3.0, 0.0), original[2])
            val looked = model.pose("idle", time, Vec3(0.0, 90.0, 0.0))
            point(Vec3(2.0, 0.0, 0.0), looked[0])
            point(Vec3(2.0, 1.0, 0.0), looked[1])
            point(Vec3(2.0, 1.0, -2.0), looked[2])
            original.forEach { it.values.fill(-900.0) }
            looked.forEach { it.values.fill(-800.0) }
            point(Vec3(2.0, 3.0, 0.0), model.pose("idle", time)[2])
            point(ZERO, model.pose(null, time)[0])
            point(ZERO, model.pose("unknown", time)[0])
        }
    }

    @Test
    fun `equal nonzero multiple keys retain interpolation rounding`() {
        val keys = listOf(0.0, 1.0).map { NpcKeyframe(it, Vec3(0.1, 0.0, 0.0), "linear") }
        val model = NpcModel(
            AssetKey("test:rounding"), LocalBounds(ZERO, ONE),
            listOf(NpcBone("body", null, ZERO, ZERO)),
            listOf(NpcPart("body", "test:body", ZERO, ZERO, ZERO)),
            mapOf("idle" to NpcClip("hold", 1.0, mapOf("body" to mapOf("position" to keys)))),
            emptyMap(),
        )
        val expected = 0.1 * (1 - 0.3) + 0.1 * 0.3
        assertEquals(expected.toRawBits(), model.pose("idle", 0.3).single().values[3].toRawBits())
    }

    @Test
    fun `large multi clip skeleton preserves late clip transforms`() {
        val bones = (0 until 256).map { NpcBone("bone-$it", null, ZERO, ZERO) }
        val clips = (0 until 5).associate { clip ->
            "clip-$clip" to NpcClip("loop", 2.0, bones.mapIndexed { index, bone ->
                bone.id to mapOf("position" to listOf(
                    NpcKeyframe(0.3, Vec3(clip.toDouble(), index.toDouble(), 0.0), "linear")
                ))
            }.toMap())
        }
        val model = NpcModel(
            AssetKey("test:many-clips"), LocalBounds(ZERO, ONE), bones,
            listOf(NpcPart("bone-255", "test:part", ZERO, ZERO, ZERO)), clips, emptyMap(),
        )
        val prepared = prepareNpcClips(bones, bones.map { ZERO }, -1, clips)
        assertEquals(1024, prepared.values.sumOf { clip -> clip.bones.count { it?.constantLocal != null } })
        for (clip in listOf(4, 0, 3, 1, 2, 4)) {
            point(Vec3(clip.toDouble(), 255.0, 0.0), model.pose("clip-$clip", 2.1).single())
        }
    }

    @Test
    fun `unused clip rotation overflow does not reject a valid model`() {
        overflowRemainsLazy("rotation")
    }

    @Test
    fun `unused clip position overflow does not reject a valid model`() {
        overflowRemainsLazy("position")
    }

    private fun overflowRemainsLazy(channel: String) {
        val origin = if (channel == "position") "[1e308,0,0]" else "[0,0,0]"
        val rotation = if (channel == "rotation") "[1e308,0,0]" else "[0,0,0]"
        val model = assertDoesNotThrow<NpcModel> {
            NpcModel.parse("""{
                "schemaVersion":1,"asset":"test:overflow","bounds":{"center":[0,0,0],"size":[1,1,1]},
                "bones":[{"id":"body","parent":null,"origin":$origin,"rotation":$rotation}],
                "parts":[{"bone":"body","model":"grounds:npc_parts/test/body","center":[0,0,0],"pivot":[0,0,0],"rotation":[0,0,0]}],
                "animations":{"safe":{"loop":"loop","length":2,"tracks":{}},
                    "overflow":{"loop":"once","length":2,"tracks":{"body":{"$channel":[{"time":0,"value":[1e308,0,0],"interpolation":"linear"}]}}}},
                "sounds":{}
            }""")
        }
        assertDoesNotThrow { model.pose(null, 0.0) }
        assertDoesNotThrow { model.pose("safe", 0.0) }
        assertThrows(IllegalArgumentException::class.java) { model.pose("overflow", 0.0) }
    }

    private fun point(expected: Vec3, matrix: NpcMatrix) {
        val actual = matrix.point(ZERO)
        assertEquals(expected.x, actual.x, 1e-10)
        assertEquals(expected.y, actual.y, 1e-10)
        assertEquals(expected.z, actual.z, 1e-10)
    }
}
