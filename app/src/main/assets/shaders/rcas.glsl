#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uTexelSize;
uniform float uSharpness; // 0.0 .. 1.0

// Упрощённый RCAS (Robust Contrast Adaptive Sharpening).
// В отличие от CAS, RCAS учитывает более широкий локальный контекст
// и сильнее подавляет резкость возле уже резких границ (меньше ореолов).
// Это урезанная версия официального AMD RCAS под мобильный GPU:
// 5 основных сэмплов + оценка локального шума по разнице соседей.

void main() {
    vec2 texel = uTexelSize;

    vec3 c  = texture(uTexture, vTexCoord).rgb;
    vec3 n  = texture(uTexture, vTexCoord + vec2(0.0, -texel.y)).rgb;
    vec3 s  = texture(uTexture, vTexCoord + vec2(0.0,  texel.y)).rgb;
    vec3 w  = texture(uTexture, vTexCoord + vec2(-texel.x, 0.0)).rgb;
    vec3 e  = texture(uTexture, vTexCoord + vec2( texel.x, 0.0)).rgb;

    // локальный контраст (простая оценка через min/max крестом)
    vec3 mn4 = min(min(n, s), min(w, e));
    vec3 mx4 = min(max(max(n, s), max(w, e)), vec3(1.0));
    vec3 mn = min(mn4, c);
    vec3 mx = max(mx4, c);

    // "robust" часть: оцениваем локальный шум/переходность,
    // чтобы не усиливать резкость там, где и так уже резко (антиореольная защита)
    vec3 ampInv = clamp(min(mn, 2.0 - mx) / max(mx, 1e-4), 0.0, 1.0);
    float lobe = mix(-0.20, -0.05, 1.0 - uSharpness); // сила лепестка резкости
    vec3 weight = lobe * sqrt(ampInv);

    vec3 numerator = weight.r * (n.r + s.r + w.r + e.r) + c.r * 1.0;
    // считаем по каналам единообразно через смешанный вес (усреднение по RGB для стабильности)
    float wAvg = (weight.r + weight.g + weight.b) / 3.0;

    vec3 sharpened = (c * (1.0 - 4.0 * wAvg)) + (n + s + w + e) * wAvg;
    sharpened = clamp(sharpened, 0.0, 1.0);

    fragColor = vec4(sharpened, 1.0);
}
