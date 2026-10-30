package gg.grounds.resourcepacks.product

/** Extend the existing GUI text shader without changing its world path or marker decoding. */
internal object SidebarHeadShader {
    private const val SITE = "void main() {\n#ifdef IS_GUI\n"

    fun extend(source: String): String {
        require(source.indexOf(SITE) >= 0 && source.indexOf(SITE) == source.lastIndexOf(SITE))
        return source.replace(SITE, SITE + SHIFT)
    }

    private val SHIFT =
        """
        // Native player-head face and hat tiles, scoped to the sidebar's rank-icon column.
        // Other text and player heads (chat/tab/world) keep their original positions.
        vec2 skinTexture = vec2(textureSize(Sampler0, 0));
        bool skinU = abs(UV0.x - 0.125) < 0.00001 || abs(UV0.x - 0.25) < 0.00001 ||
                     abs(UV0.x - 0.625) < 0.00001 || abs(UV0.x - 0.75) < 0.00001;
        bool skinV = abs(UV0.y - 0.125) < 0.00001 || abs(UV0.y - 0.25) < 0.00001;
        if (all(equal(skinTexture, vec2(64.0))) && skinU && skinV) {
            vec4 skinClip = ProjMat * ModelViewMat * vec4(Position, 1.0);
            vec2 skinViewport = abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1]));
            vec2 skinLogical = ceil(skinViewport - vec2(0.001));
            vec2 skinPosition = (vec2(skinClip.x, -skinClip.y) / skinClip.w * 0.5 + 0.5) * skinViewport;
            float skinLeft = skinLogical.x - 111.0 + 9.0;
            for (int rows = 11; rows <= 12; rows++) {
                float skinTop = floor(skinLogical.y / 2.0) - 6.0 * rows - 1.0 + 9.0;
                bool skinX = abs(skinPosition.x - skinLeft) < 0.05 || abs(skinPosition.x - (skinLeft + 8.0)) < 0.05;
                bool skinY = abs(skinPosition.y - skinTop) < 0.05 || abs(skinPosition.y - (skinTop + 8.0)) < 0.05;
                if (skinX && skinY) {
                    gl_Position = skinClip;
                    gl_Position.y -= 10.0 / skinViewport.y * skinClip.w;
                    vertexColor = Color;
                    texCoord0 = UV0;
                    return;
                }
            }
        }

        """
            .trimIndent() + "\n"
}
