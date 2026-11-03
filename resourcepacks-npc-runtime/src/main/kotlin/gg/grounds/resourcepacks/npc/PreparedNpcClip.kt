package gg.grounds.resourcepacks.npc

import gg.grounds.scene.format.Vec3

internal class PreparedNpcClip(val source: NpcClip, val bones: Array<PreparedNpcBone?>)

internal class PreparedNpcBone(
    val position: List<NpcKeyframe>,
    val rotation: List<NpcKeyframe>,
    val scale: List<NpcKeyframe>,
    val constantLocal: NpcMatrix?,
    val headPrefix: NpcMatrix?,
    val headScale: NpcMatrix?,
)

internal fun prepareNpcClips(
    bones: List<NpcBone>,
    offsets: List<Vec3>,
    headIndex: Int,
    animations: Map<String, NpcClip>,
): Map<String, PreparedNpcClip> {
    var constants = 0
    return animations.mapValues { (_, clip) ->
        PreparedNpcClip(clip, Array(bones.size) { index ->
            val channels = clip.tracks[bones[index].id].orEmpty()
            if (channels.values.all { it.isEmpty() }) null
            else {
                val position = channels["position"].orEmpty()
                val rotation = channels["rotation"].orEmpty()
                val scale = channels["scale"].orEmpty()
                // A multi-key constant-looking curve can still have different rounding.
                val singleton = constants < MAX_PREPARED_CONSTANT_BONES &&
                    listOf(position, rotation, scale).all { keys ->
                        keys.size <= 1 && keys.all { it.time.isFinite() }
                    }
                val positionValue = if (singleton) sampleNpcTrack(position, 0.0, ZERO) else ZERO
                val rotationValue = if (singleton) sampleNpcTrack(rotation, 0.0, ZERO) else ZERO
                // Keep arithmetic failures local to playing that clip, as before preparation.
                val constant = singleton && finiteSum(offsets[index], positionValue) &&
                    finiteSum(bones[index].rotation, rotationValue)
                var prefix: NpcMatrix? = null
                var scaleMatrix: NpcMatrix? = null
                val local = if (constant) {
                    constants++
                    prefix = NpcMatrix.translation(
                        offsets[index] + positionValue
                    ) * NpcMatrix.rotation(
                        bones[index].rotation + rotationValue
                    )
                    scaleMatrix = NpcMatrix.scale(sampleNpcTrack(scale, 0.0, ONE))
                    // Preserve the dynamic path's operation order, including identity look.
                    prefix * NpcMatrix.IDENTITY * scaleMatrix
                } else null
                PreparedNpcBone(
                    position, rotation, scale, local,
                    if (index == headIndex) prefix else null,
                    if (index == headIndex) scaleMatrix else null,
                )
            }
        })
    }
}

// Bounds retained matrix data even for a maximum-size compiled model.
private const val MAX_PREPARED_CONSTANT_BONES = 1024

private fun finiteSum(a: Vec3, b: Vec3): Boolean =
    (a.x + b.x).isFinite() && (a.y + b.y).isFinite() && (a.z + b.z).isFinite()
