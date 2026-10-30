#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_shader_draw_parameters : enable

// Vanilla 26.3 gui.vsh plus two varyings that let gui.fsh measure the quad it belongs to.
// See gui.fsh for why.
//
// 26.3 reordered DynamicTransforms (TextureMat moved up) and requires explicit locations on
// every stage input/output. std140 reads members by offset, so the block must match vanilla
// exactly - a 26.2 copy reads ColorModulator out of TextureMat and breaks every GUI fill.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 quadCorner;
layout(location = 2) out vec2 guiPos;
layout(location = 3) out vec2 screenPos;
layout(location = 4) flat out vec2 guiSize;
layout(location = 5) flat out vec4 sidebarBounds;
layout(location = 6) flat out float sidebarKind;

#ifdef VULKAN
    #define CORNER (gl_VertexIndex % 4)
#elif defined(GL_ARB_shader_draw_parameters)
    #define CORNER (int(gl_VertexID - gl_BaseVertexARB) % 4)
#else
    #define CORNER (int(gl_VertexID) % 4)
#endif

const vec2[] CORNERS = vec2[](vec2(-1, -1), vec2(-1, 1), vec2(1, 1), vec2(1, -1));

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    quadCorner = CORNERS[CORNER];
    guiPos = Position.xy;
    vec2 projectedGuiSize = abs(vec2(2.0 / ProjMat[0][0], 2.0 / ProjMat[1][1]));
    // Minecraft rounds logical GUI dimensions up; projection extents may stay fractional.
    // Use the raw extent for positions, otherwise scale 3 shifts the measured right anchor.
    guiSize = ceil(projectedGuiSize - vec2(0.001));
    screenPos = (vec2(gl_Position.x, -gl_Position.y) / gl_Position.w * 0.5 + 0.5) * projectedGuiSize;
    sidebarBounds = vec4(0.0);
    sidebarKind = 0.0;
    // Exact native 26.3 sidebar corners: 106px content and eleven/twelve actual rows.
    // Expand the original body once, so logo and contents share the user's native opacity.
    if (all(lessThanEqual(abs(Color.rgb), vec3(0.5 / 255.0)))) {
        float left = guiSize.x - 111.0;
        float right = guiSize.x - 1.0;
        float middle = floor(guiSize.y / 2.0);
        for (int rows = 11; rows <= 12; rows++) {
            float top = middle - 6.0 * rows - 1.0;
            float bottom = middle + 3.0 * rows;
            vec2 bodyCorner = vec2(CORNERS[CORNER].x < 0.0 ? left : right,
                                   CORNERS[CORNER].y < 0.0 ? top : bottom);
            vec2 titleCorner = vec2(bodyCorner.x, CORNERS[CORNER].y < 0.0 ? top - 9.0 : top);
            if (all(lessThan(abs(screenPos - bodyCorner), vec2(0.05)))) {
                sidebarKind = 1.0;
                sidebarBounds = vec4(left, top - 24.0, projectedGuiSize.x, bottom + 4.0);
                vec2 target = vec2(CORNERS[CORNER].x < 0.0 ? left : projectedGuiSize.x,
                                   CORNERS[CORNER].y < 0.0 ? top - 24.0 : bottom + 4.0);
                vec2 delta = target - screenPos;
                gl_Position.xy += vec2(2.0 * delta.x / projectedGuiSize.x,
                                       -2.0 * delta.y / projectedGuiSize.y) * gl_Position.w;
                screenPos = target;
                break;
            }
            if (all(lessThan(abs(screenPos - titleCorner), vec2(0.05)))) {
                sidebarKind = 2.0;
                break;
            }
        }
    }
}
