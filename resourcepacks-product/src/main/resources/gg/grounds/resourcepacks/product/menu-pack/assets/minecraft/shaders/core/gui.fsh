#version 330
#extension GL_ARB_separate_shader_objects : require

// Vanilla 26.3 gui.fsh, minus the focus outline of a dialog body.
//
// Clicking a click_event inside a dialog body focuses the body widget, which then outlines
// itself with four 1px pure-white fills. Nothing server-side can turn that off, and it cuts
// straight through a custom-drawn menu, so the fills are recognised here by their signature -
// opaque 0xFFFFFFFF and at most one GUI pixel thin - and discarded.
//
// The DynamicTransforms block must match vanilla 26.3 member for member; see gui.vsh.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 quadCorner;
layout(location = 2) in vec2 guiPos;
layout(location = 3) in vec2 screenPos;
layout(location = 4) flat in vec2 guiSize;
layout(location = 5) flat in vec4 sidebarBounds;
layout(location = 6) flat in float sidebarKind;

layout(location = 0) out vec4 fragColor;

const float WHITE = 254.5 / 255.0;
const float MAX_THICKNESS = 1.5;

// Quad size in GUI pixels: quadCorner spans 2 units across the quad, guiPos spans its size,
// and taking both derivatives in the same fragment cancels out the GUI scale.
vec2 quad_size() {
    return 2.0 * fwidth(guiPos) / max(fwidth(quadCorner), vec2(1e-6));
}

bool is_focus_outline(vec4 color) {
    if (any(lessThan(color, vec4(WHITE)))) return false;
    vec2 size = quad_size();
    return min(size.x, size.y) <= MAX_THICKNESS;
}

// The body quad is the sole background, extended by gui.vsh over the logo and to the edge.
float sidebar_corner_coverage() {
    if (sidebarKind > 1.5) return 0.0;
    // The title's bottom vertices coincide with the body's top vertices. Recover the
    // original height independent of the batched base-vertex phase and drop that fill.
    float originalHeight = 2.0 * abs(dFdy(guiPos.y)) /
        max(max(abs(dFdy(quadCorner.x)), abs(dFdy(quadCorner.y))), 1e-6);
    if (sidebarKind > 0.5 && abs(originalHeight - 9.0) < 0.05) return 0.0;
    if (sidebarKind < 0.5) return 1.0;
    float dx = screenPos.x - sidebarBounds.x;
    float dy = min(screenPos.y - sidebarBounds.y, sidebarBounds.w - screenPos.y);
    const float radius = 4.0;
    if (dx >= radius || dy >= radius) return 1.0;
    float distance = length(vec2(radius - dx, radius - dy)) - radius;
    float aa = max(fwidth(distance) * 0.5, 0.001);
    return 1.0 - smoothstep(-aa, aa, distance);
}

void main() {
    vec4 color = vertexColor;
    if (color.a == 0.0 || is_focus_outline(color)) {
        discard;
    }
    color.a *= sidebar_corner_coverage();
    if (color.a == 0.0) discard;
    fragColor = color * ColorModulator;
}
