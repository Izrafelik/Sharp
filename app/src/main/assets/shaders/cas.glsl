#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;   // 1.0 / textureResolution
uniform float uSharpness;  // 0.0 .. 1.0, интенсивность

// Упрощённый CAS: адаптивная резкость на основе локального контраста
// (по мотивам AMD FidelityFX CAS, урезано до минимального 5-тапа
// без even/odd оптимизации — этого достаточно для одного полноэкранного прохода)

void main() {
    vec2 texel = uTexelSize;

    // 5 сэмплов крестом: центр + 4 соседа
    vec3 a = texture(uTexture, vTexCoord + vec2(0.0, -texel.y)).rgb; // top
    vec3 b = texture(uTexture, vTexCoord + vec2(-texel.x, 0.0)).rgb; // left
    vec3 c = texture(uTexture, vTexCoord).rgb;                       // center
    vec3 d = texture(uTexture, vTexCoord + vec2(texel.x, 0.0)).rgb;  // right
    vec3 e = texture(uTexture, vTexCoord + vec2(0.0, texel.y)).rgb;  // bottom

    // локальный min/max по каждому каналу (для адаптивности CAS)
    vec3 mn = min(min(min(min(a, b), c), d), e);
    vec3 mx = max(max(max(max(a, b), c), d), e);

    // адаптивный вес резкости — сильнее там, где меньше локальный контраст,
    // это и есть суть CAS: не переусиливать уже контрастные края
    vec3 rangeInv = 1.0 / max(mx - mn, vec3(1e-4));
    vec3 weight = clamp((min(mn, vec3(1.0) - mx)) * rangeInv, 0.0, 1.0);
    weight = sqrt(weight); // смягчение отклика, как в оригинальном CAS

    float w = -mix(0.0, 0.25, uSharpness) * max(weight.r, max(weight.g, weight.b));

    vec3 sharpened = (a + b + d + e) * w + c * (1.0 - 4.0 * w);
    sharpened = clamp(sharpened, 0.0, 1.0);

    fragColor = vec4(sharpened, 1.0);
}
