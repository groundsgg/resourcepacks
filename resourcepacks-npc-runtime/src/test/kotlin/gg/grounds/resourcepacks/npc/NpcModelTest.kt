package gg.grounds.resourcepacks.npc

import gg.grounds.scene.format.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcModelTest {
    @Test
    fun `client item display rotation preserves authored head orientation and pivot`() {
        val model =
            NpcModel(
                gg.grounds.scene.format.AssetKey("grounds:npc/test/worker"),
                gg.grounds.scene.format.LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                listOf(NpcBone("head", null, Vec3(0.0, 1.5, 0.0), Vec3(12.0, 20.0, -7.0))),
                listOf(
                    NpcPart(
                        "head",
                        "grounds:npc_parts/test/worker/part_000",
                        Vec3(0.1, 1.7, -0.05),
                        Vec3(0.0, 1.5, 0.0),
                        Vec3(22.5, 0.0, 0.0),
                    )
                ),
                mapOf("idle" to NpcClip("loop", 1.0, emptyMap())),
                emptyMap(),
            )
        val authored = model.pose("idle", 0.0).single()
        // DisplayRenderer.ItemDisplayRenderer applies this AFTER the entity transformation.
        val client =
            model.itemDisplayPose("idle", 0.0).single() * NpcMatrix.rotation(Vec3(0.0, 180.0, 0.0))
        for (point in
            listOf(ZERO, Vec3(0.0, 0.0, -0.5), Vec3(0.5, 0.0, 0.0), Vec3(0.0, 0.5, 0.0))) {
            val expected = authored.point(point)
            val actual = client.point(point)
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
            assertEquals(expected.z, actual.z, 1e-9)
        }
    }

    @Test
    fun `looping Catmull Rom curves wrap their tangent keys at both ends`() {
        val keys =
            listOf(91.6409, 92.7091, 91.6409, 88.6579, 91.6409).mapIndexed { i, value ->
                NpcKeyframe(i.toDouble(), Vec3(value, 0.0, 0.0), "catmullrom")
            }
        assertEquals(92.4282, sampleNpcTrack(keys, 0.5, ZERO, true).x, 1e-7)
        assertEquals(89.8962, sampleNpcTrack(keys, 3.5, ZERO, true).x, 1e-7)
    }

    @Test
    fun `linear and Catmull Rom samples retain the source curve rather than snapping keys`() {
        val linear =
            listOf(
                NpcKeyframe(0.0, Vec3(0.0, 0.0, 0.0), "linear"),
                NpcKeyframe(2.0, Vec3(4.0, 0.0, 0.0), "linear"),
            )
        assertEquals(1.0, sampleNpcTrack(linear, 0.5, Vec3(9.0, 9.0, 9.0)).x, 1e-9)
        val curve =
            listOf(
                NpcKeyframe(0.0, Vec3(0.0, 0.0, 0.0), "catmullrom"),
                NpcKeyframe(1.0, Vec3(2.0, 0.0, 0.0), "catmullrom"),
                NpcKeyframe(2.0, Vec3(0.0, 0.0, 0.0), "catmullrom"),
            )
        assertEquals(1.125, sampleNpcTrack(curve, 0.5, Vec3(0.0, 0.0, 0.0)).x, 1e-9)
        assertEquals(2.0, sampleNpcTrack(curve, 1.0, Vec3(0.0, 0.0, 0.0)).x, 1e-9)
    }

    @Test
    fun `nested bones rotate cube centers around the source pivots`() {
        val model =
            NpcModel.parse(
                """{
          "schemaVersion":1,"asset":"grounds:npc/test/worker","bounds":{"center":[0,1,0],"size":[1,2,1]},
          "bones":[{"id":"root","name":"root","parent":null,"origin":[0,0,0],"rotation":[0,0,0]},
                   {"id":"arm","name":"arm","parent":"root","origin":[0,1,0],"rotation":[0,0,0]}],
          "parts":[{"bone":"arm","model":"grounds:npc_parts/test/worker/part_000","center":[1,1,0],"pivot":[1,1,0],"rotation":[0,0,0]}],
          "animations":{"idle":{"loop":"loop","length":2,"tracks":{"root":{"position":[{"time":0,"value":[2,0,0],"interpolation":"linear"}]},"arm":{"rotation":[{"time":0,"value":[0,0,90],"interpolation":"linear"}]}}}},"sounds":{}
        }"""
            )
        val pose = model.pose("idle", 0.0).single()
        val center = pose.point(Vec3(0.0, 0.0, 0.0))
        assertEquals(2.0, center.x, 1e-9)
        assertEquals(2.0, center.y, 1e-9)
        assertEquals(0.0, center.z, 1e-9)
        val inverted =
            model.copy(parts = model.parts.map { it.copy(scale = Vec3(-1.0, -1.0, -1.0)) })
        val edge = inverted.pose("idle", 0.0).single().point(Vec3(1.0, 0.0, 0.0))
        assertEquals(2.0, edge.x, 1e-9)
        assertEquals(1.0, edge.y, 1e-9)
    }

    @Test
    fun `malformed models cannot introduce missing bones cyclic hierarchy or expressions`() {
        assertThrows(IllegalArgumentException::class.java) { NpcModel.parse("{}") }
    }
}
