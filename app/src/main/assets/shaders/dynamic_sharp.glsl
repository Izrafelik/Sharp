#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;
uniform float uSharpness; // 0.0 .. 1.0

// Простейший "динамический" unsharp mask: 4 сэмпла вместо 5 (без центра отдельно),
// сила резкости адаптируется под локальный контраст одним умножением —
// это самый дешёвый по ALU из трёх вариантов.

void main() {
    vec2 texel = uTexelSize;
    vec3 c = texture(uTexture, vTexCoord).rgb;

    vec3 blurSum =
        texture(uTexture, vTexCoord + vec2(texel.x, 0.0)).rgb +
        texture(uTexture, vTexCoord + vec2(-texel.x, 0.0)).rgb +
        texture(uTexture, vTexCoord + vec2(0.0, texel.y)).rgb +
        texture(uTexture, vTexCoord + vec2(0.0, -texel.y)).rgb;
    vec3 blur = blurSum * 0.25;

    vec3 diff = c - blur; // высокочастотная составляющая

    // динамическая адаптация: чем меньше локальный перепад, тем меньше усиление
    // (простая защита от шума на плоских участках, дешевле чем CAS/RCAS)
    float localContrast = clamp(length(diff) * 4.0, 0.0, 1.0);
    float amount = uSharpness * 1.5 * localContrast;

    vec3 result = clamp(c + diff * amount, 0.0, 1.0);
    fragColor = vec4(result, 1.0);
}
