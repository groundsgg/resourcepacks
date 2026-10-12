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
}
