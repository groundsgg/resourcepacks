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

// Native 26.3 branded sidebar: 134px content, thirteen 9px rows, blank score numbers.
// Match width, height, right anchor AND vertical bounds, never black/alpha alone.
// Legacy GroundsSidebar has at most twelve rows and does not match these bounds.
float sidebar_corner_coverage(vec4 color) {
    if (any(greaterThan(abs(color.rgb), vec3(0.5 / 255.0)))) return 1.0;
    vec2 size = quad_size();
    if (abs(size.x - 138.0) > 0.05) return 1.0;
    float left = guiSize.x - 139.0;
    float right = guiSize.x - 1.0;
    float middle = floor(guiSize.y / 2.0);
    bool title = abs(size.y - 9.0) < 0.05;
    bool body = abs(size.y - 118.0) < 0.05;
    if (!title && !body) return 1.0;
    float top = middle - (title ? 88.0 : 79.0);
    float bottom = middle + (title ? -79.0 : 39.0);
    // Screen-space derivatives recover the actual quad bounds, including GUI pose transforms.
    vec2 span = vec2(dFdx(quadCorner.x), dFdy(quadCorner.y));
    if (any(lessThan(abs(span), vec2(1e-6)))) return 1.0;
    vec2 center = screenPos - quadCorner * vec2(dFdx(screenPos.x), dFdy(screenPos.y)) / span;
    vec2 wantedCenter = vec2((left + right) * 0.5, (top + bottom) * 0.5);
    if (any(greaterThan(abs(center - wantedCenter), vec2(0.05)))) return 1.0;
    // Keep the unique thirteen-row native signature, but trim its two unused tail rows.
    // The eleven visible rows have one spacer between the three information pairs.
    float visibleBottom = body ? bottom - 18.0 : bottom;
    if (body && screenPos.y >= visibleBottom) return 0.0;
    float dx = min(screenPos.x - left, right - screenPos.x);
    float dy = title ? screenPos.y - top : visibleBottom - screenPos.y;
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
    color.a *= sidebar_corner_coverage(color);
    if (color.a == 0.0) discard;
    fragColor = color * ColorModulator;
}
