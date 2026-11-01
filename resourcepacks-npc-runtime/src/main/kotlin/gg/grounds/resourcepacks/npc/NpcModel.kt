package gg.grounds.resourcepacks.npc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import kotlin.math.cos
import kotlin.math.sin

data class NpcKeyframe(val time: Double, val value: Vec3, val interpolation: String)

data class NpcBone
@JvmOverloads
constructor(
    val id: String,
    val parent: String?,
    val origin: Vec3,
    val rotation: Vec3,
    val name: String = id,
)

data class NpcPart(
    val bone: String?,
    val model: String,
    val center: Vec3,
    val pivot: Vec3,
    val rotation: Vec3,
    val scale: Vec3 = Vec3(1.0, 1.0, 1.0),
)

data class NpcClip(
    val loop: String,
    val length: Double,
    val tracks: Map<String, Map<String, List<NpcKeyframe>>>,
)

data class NpcClipSound(val key: String, val volume: Double, val pitch: Double, val radius: Double)

/** Compiled, numeric asset data. Parsing never evaluates authored expressions or scripts. */
data class NpcModel(
    val asset: AssetKey,
    val bounds: LocalBounds,
    val bones: List<NpcBone>,
    val parts: List<NpcPart>,
    val animations: Map<String, NpcClip>,
    val sounds: Map<String, NpcClipSound>,
) {
    val headBone: NpcBone?
        get() = bones.singleOrNull { it.name.equals("head", ignoreCase = true) }

    /** Minecraft rotates each ItemDisplay mesh by pi after its entity transformation. */
    @JvmOverloads
    fun itemDisplayPose(
        animation: String?,
        seconds: Double,
        headLook: Vec3? = null,
    ): List<NpcMatrix> {
        val inverseClientRotation = NpcMatrix.rotation(Vec3(0.0, 180.0, 0.0))
        return pose(animation, seconds, headLook).map { it * inverseClientRotation }
    }

    @JvmOverloads
    fun pose(animation: String?, seconds: Double, headLook: Vec3? = null): List<NpcMatrix> {
        val clip = animation?.let(animations::get)
        val time = if (clip?.loop == "loop") seconds % clip.length else seconds
        val world = mutableMapOf<String, NpcMatrix>()
        val origins = bones.associate { it.id to it.origin }
        for (bone in bones) {
            val channels = clip?.tracks?.get(bone.id).orEmpty()
            val position =
                sampleNpcTrack(channels["position"].orEmpty(), time, ZERO, clip?.loop == "loop")
            val rotation =
                bone.rotation +
                    sampleNpcTrack(channels["rotation"].orEmpty(), time, ZERO, clip?.loop == "loop")
            val scale = sampleNpcTrack(channels["scale"].orEmpty(), time, ONE, clip?.loop == "loop")
            val local =
                NpcMatrix.translation(
                    bone.origin - (bone.parent?.let(origins::get) ?: ZERO) + position
                ) *
                    NpcMatrix.rotation(rotation) *
                    (if (headLook != null && bone.id == headBone?.id) NpcMatrix.rotation(headLook)
                    else NpcMatrix.IDENTITY) *
                    NpcMatrix.scale(scale)
            world[bone.id] = (bone.parent?.let(world::get) ?: NpcMatrix.IDENTITY) * local
        }
        return parts.map { part ->
            (part.bone?.let(world::get) ?: NpcMatrix.IDENTITY) *
                NpcMatrix.translation(part.pivot - (part.bone?.let(origins::get) ?: ZERO)) *
                NpcMatrix.rotation(part.rotation) *
                NpcMatrix.translation(part.center - part.pivot) *
                NpcMatrix.scale(part.scale)
        }
    }

    companion object {
        fun parse(json: String): NpcModel {
            val root = JsonParser.parseString(json).asJsonObject
            require(root.get("schemaVersion")?.asInt == 1) { "Unsupported NPC model schema" }
            fun vector(obj: JsonObject, name: String): Vec3 {
                val values = requireNotNull(obj.getAsJsonArray(name)) { "Missing $name" }
                require(values.size() == 3)
                return Vec3(values[0].asDouble, values[1].asDouble, values[2].asDouble)
            }
            val bounds = root.getAsJsonObject("bounds")
            val bones =
                root.getAsJsonArray("bones").map { item ->
                    val bone = item.asJsonObject
                    NpcBone(
                        bone.get("id").asString,
                        bone.get("parent")?.takeUnless { it.isJsonNull }?.asString,
                        vector(bone, "origin"),
                        vector(bone, "rotation"),
                        bone.get("name")?.asString ?: bone.get("id").asString,
                    )
                }
            require(bones.size <= 256)
            val known = mutableSetOf<String>()
            bones.forEach { bone ->
                require(bone.parent == null || bone.parent in known) {
                    "Missing or cyclic NPC parent"
                }
                require(known.add(bone.id)) { "Duplicate bone" }
            }
            val parts =
                root.getAsJsonArray("parts").map { item ->
                    val part = item.asJsonObject
                    val bone = part.get("bone")?.takeUnless { it.isJsonNull }?.asString
                    require(bone == null || bone in known)
                    val model = AssetKey(part.get("model").asString).value
                    require(model.startsWith("grounds:npc_parts/"))
                    NpcPart(
                        bone,
                        model,
                        vector(part, "center"),
                        vector(part, "pivot"),
                        vector(part, "rotation"),
                        if (part.has("scale")) vector(part, "scale") else ONE,
                    )
                }
            require(parts.size in 1..1024)
            val clips =
                root.getAsJsonObject("animations").entrySet().associate { (name, item) ->
                    LocalId(name)
                    val clip = item.asJsonObject
                    val length = clip.get("length").asDouble
                    require(length.isFinite() && length > 0 && length <= 300)
                    val loop = clip.get("loop").asString
                    require(loop in setOf("loop", "once", "hold"))
                    val tracks =
                        clip.getAsJsonObject("tracks").entrySet().associate { (bone, trackItem) ->
                            require(bone in known)
                            bone to
                                trackItem.asJsonObject.entrySet().associate { (channel, frames) ->
                                    require(channel in setOf("position", "rotation", "scale"))
                                    val keys =
                                        frames.asJsonArray.map { frameItem ->
                                            val frame = frameItem.asJsonObject
                                            val time = frame.get("time").asDouble
                                            val interpolation = frame.get("interpolation").asString
                                            require(
                                                time.isFinite() &&
                                                    time >= 0 &&
                                                    time <= length + 1e-4
                                            )
                                            require(
                                                interpolation in
                                                    setOf("linear", "catmullrom", "step")
                                            )
                                            NpcKeyframe(time, vector(frame, "value"), interpolation)
                                        }
                                    require(
                                        keys.size <= 4096 &&
                                            keys.zipWithNext().all { (a, b) -> a.time <= b.time }
                                    )
                                    channel to keys
                                }
                        }
                    name to NpcClip(loop, length, tracks)
                }
            require(clips.size in 1..128)
            val sounds =
                root.getAsJsonObject("sounds").entrySet().associate { (clip, item) ->
                    require(clip in clips)
                    val value = item.asJsonObject
                    val key = AssetKey(value.get("key").asString).value
                    val volume = value.get("volume").asDouble
                    val pitch = value.get("pitch").asDouble
                    val radius = value.get("radius").asDouble
                    require(
                        volume.isFinite() &&
                            volume > 0 &&
                            volume <= 4 &&
                            pitch.isFinite() &&
                            pitch > 0 &&
                            radius.isFinite() &&
                            radius > 0 &&
                            radius <= 64
                    )
                    clip to NpcClipSound(key, volume, pitch, radius)
                }
            return NpcModel(
                AssetKey(root.get("asset").asString),
                LocalBounds(vector(bounds, "center"), vector(bounds, "size")),
                bones,
                parts,
                clips,
                sounds,
            )
        }
    }
}

/**
 * Matches Blockbench Keyframe.getCatmullromLerp / THREE.SplineCurve, including endpoint duplication
 * for one-shots and wrapped tangent keys for looping clips.
 */
fun sampleNpcTrack(
    keys: List<NpcKeyframe>,
    time: Double,
    fallback: Vec3,
    loop: Boolean = false,
): Vec3 {
    if (keys.isEmpty()) return fallback
    if (time < keys.first().time) return keys.first().value
    val before = keys.indexOfLast { it.time <= time }
    if (before == keys.lastIndex) return keys.last().value
    val a = keys[before]
    val b = keys[before + 1]
    if (a.interpolation == "step") return a.value
    val t = (time - a.time) / (b.time - a.time)
    if (a.interpolation != "catmullrom" && b.interpolation != "catmullrom")
        return a.value * (1 - t) + b.value * t
    val p0 =
        keys.getOrNull(before - 1)?.value
            ?: if (loop && keys.size >= 3) keys[keys.lastIndex - 1].value else a.value
    val p3 =
        keys.getOrNull(before + 2)?.value ?: if (loop && keys.size >= 3) keys[1].value else b.value
    fun c(v0: Double, v1: Double, v2: Double, v3: Double): Double =
        (2 * v1 +
            (-v0 + v2) * t +
            (2 * v0 - 5 * v1 + 4 * v2 - v3) * t * t +
            (-v0 + 3 * v1 - 3 * v2 + v3) * t * t * t) / 2
    return Vec3(
        c(p0.x, a.value.x, b.value.x, p3.x),
        c(p0.y, a.value.y, b.value.y, p3.y),
        c(p0.z, a.value.z, b.value.z, p3.z),
    )
}

val ZERO = Vec3(0.0, 0.0, 0.0)
val ONE = Vec3(1.0, 1.0, 1.0)

operator fun Vec3.plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

operator fun Vec3.minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

operator fun Vec3.times(scale: Double) = Vec3(x * scale, y * scale, z * scale)

class NpcMatrix(val values: DoubleArray) {
    operator fun times(other: NpcMatrix) =
        NpcMatrix(
            DoubleArray(16) { index ->
                (0..3).sumOf { values[(index / 4) * 4 + it] * other.values[it * 4 + index % 4] }
            }
        )

    fun point(p: Vec3) =
        Vec3(
            values[0] * p.x + values[1] * p.y + values[2] * p.z + values[3],
            values[4] * p.x + values[5] * p.y + values[6] * p.z + values[7],
            values[8] * p.x + values[9] * p.y + values[10] * p.z + values[11],
        )

    companion object {
        val IDENTITY =
            NpcMatrix(
                doubleArrayOf(
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                )
            )

        fun translation(p: Vec3) =
            NpcMatrix(
                IDENTITY.values.copyOf().also {
                    it[3] = p.x
                    it[7] = p.y
                    it[11] = p.z
                }
            )

        fun scale(p: Vec3) =
            NpcMatrix(
                IDENTITY.values.copyOf().also {
                    it[0] = p.x
                    it[5] = p.y
                    it[10] = p.z
                }
            )

        fun rotation(degrees: Vec3): NpcMatrix {
            val x = Math.toRadians(degrees.x)
            val y = Math.toRadians(degrees.y)
            val z = Math.toRadians(degrees.z)
            val rx =
                NpcMatrix(
                    doubleArrayOf(
                        1.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        cos(x),
                        -sin(x),
                        0.0,
                        0.0,
                        sin(x),
                        cos(x),
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                    )
                )
            val ry =
                NpcMatrix(
                    doubleArrayOf(
                        cos(y),
                        0.0,
                        sin(y),
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        -sin(y),
                        0.0,
                        cos(y),
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                    )
                )
            val rz =
                NpcMatrix(
                    doubleArrayOf(
                        cos(z),
                        -sin(z),
                        0.0,
                        0.0,
                        sin(z),
                        cos(z),
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                    )
                )
            return rz * ry * rx
        }
    }
}
