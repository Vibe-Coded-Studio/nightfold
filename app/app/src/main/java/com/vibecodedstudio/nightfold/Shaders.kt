package com.vibecodedstudio.nightfold

/** Effects in shader order (index = the fx uniform). The letters are the names shown in the app and stay fixed. */
val EFFECTS = listOf(
    "K  Stardust", "L  Pixel Assemble", "M  Sandstorm", "N  Sketch to Paint",
    "O  Hologram", "P  Crystal", "Q  Raindrops", "R  Eclipse Bloom",
    "S  Corona", "T  Moon Shards", "U  Constellation", "V  Nebula Burn", "W  Blood Moon",
    "X  Solar Flare", "AA  Eclipse Ink", "AB  Fireflies", "AC  Ember Burn", "AD  Starfall", "AE  Petal Storm",
    "AF  Frost Bloom", "AG  Aurora", "AH  Glitch Warp", "AI  Honeycomb Bloom", "AJ  Lightning Veins", "AK  Stained Glass",
    "AL  Tile Flip", "AM  Spiral Galaxy", "AN  Kintsugi", "AO  Flower Bloom", "AP  Frosted Glass",
    "AQ  Snow Day", "AR  Autumn Leaves", "AS  Gift Wrap",
)

/** Effect list groups for the app's list (indices into EFFECTS). */
val FX_GROUPS = listOf(listOf(7, 8, 10, 11), listOf(30, 31, 32), (22..29).toList(), (14..21).toList(), listOf(0, 1, 3, 4, 5, 6))

/** Not listed in the app (M Sandstorm, T Moon Shards, W Blood Moon, X Solar Flare), but kept in the shader so saved indices stay valid. */
val FX_HIDDEN = setOf(2, 9, 12, 13)

/** Default effect: R Eclipse Bloom. */
const val DEFAULT_FX = 7

/** Saved effect index. Key "fx2" holds the current numbering; a value under the older key "fx" (where K-R were 10-17) is migrated once. */
fun savedFx(sp: android.content.SharedPreferences): Int {
    if (!sp.contains("fx2")) {
        val old = sp.getInt("fx", -1)
        sp.edit().putInt("fx2", if (old >= 10) old - 10 else DEFAULT_FX).apply()
    }
    // a hidden effect (FX_HIDDEN) falls back to the default
    return sp.getInt("fx2", DEFAULT_FX).coerceIn(0, EFFECTS.size - 1).let { if (it in FX_HIDDEN) DEFAULT_FX else it }
}

/** Wave amplitude and trailing-band length per effect, in layout units (pixels / S; screen height = 936). */
val AMP = floatArrayOf(18f, 10f, 26f, 30f, 8f, 20f, 16f, 24f, 20f, 16f, 18f, 30f, 24f,
    22f, 26f, 20f, 24f, 18f, 22f, 28f, 30f, 12f, 22f, 24f, 20f, 18f, 22f, 24f, 22f, 4f, 14f, 20f, 16f)
val TAIL = floatArrayOf(620f, 640f, 560f, 700f, 600f, 620f, 640f, 620f, 640f, 620f, 680f, 620f, 640f,
    620f, 660f, 660f, 660f, 640f, 640f, 640f, 680f, 600f, 640f, 660f, 640f, 640f, 660f, 620f, 660f, 1100f, 760f, 660f, 600f)

/**
 * One AGSL shader for every effect. The sweep: a wavy front travels from the outer edge toward the hinge
 * (right -> left). On the cover screen the picture leaves (arriving = 0); on the inner screen it arrives (arriving = 1).
 * With slide = 1 the picture itself moves with the front. d = x - front: d > 0 = already swept.
 * Images: img0 full size, img1 = 1/4, img2 = 1/16, img3 = 1/64 (box-filtered), used as cheap blur levels 0..3.
 */
private const val FX_SHADER_A = """
uniform float2 res;
uniform float2 isz;
uniform float S;
uniform float prog;
uniform float t;
uniform float arriving;
uniform float slide;
uniform float amp;
uniform float tail;
uniform int fx;
uniform float fin;       // finish pass 0..1, -1 = not in it
uniform int finMode;     // 0 off, 1 A back the other way, 2 B same way again, 3 C out from the middle
uniform float soft;      // blur of the built picture until the finish pass makes it sharp (0 = none)
uniform float wobble;    // how far the built picture wobbles until the finish pass (0 = still)
uniform float depth;     // how far the finish pass dims the picture before it rebuilds it
uniform shader img0;
uniform shader img1;
uniform shader img2;
uniform shader img3;

float ss(float a, float b, float x) { float k = clamp((x - a) / (b - a), 0.0, 1.0); return k * k * (3.0 - 2.0 * k); }
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float vnoise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

half3 samp(float2 q, float lvl) {
    float2 px = q * S * (isz / res);
    if (px.x < 0.0 || px.y < 0.0 || px.x >= isz.x || px.y >= isz.y) return half3(0.0);
    lvl = clamp(lvl, 0.0, 3.0);
    half3 c;
    if (lvl < 1.0) c = mix(img0.eval(px).rgb, img1.eval(px * 0.25).rgb, half(lvl));
    else if (lvl < 2.0) c = mix(img1.eval(px * 0.25).rgb, img2.eval(px * 0.0625).rgb, half(lvl - 1.0));
    else c = mix(img2.eval(px * 0.0625).rgb, img3.eval(px * 0.015625).rgb, half(lvl - 2.0));
    return c;
}

float3 im(float2 q, float lvl) { return float3(samp(q, lvl)); }
float sq(float x) { return x * x; }
float2 seed(float2 g) { return g + 0.15 + 0.7 * float2(hash(g), hash(g + 19.0)); }
float segd(float2 p, float2 a, float2 b) { float2 ab = b - a; float h = clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0); return length(p - a - ab * h); }
float fbm(float2 p) { return 0.55 * vnoise(p) + 0.3 * vnoise(p * 2.1 + 7.0) + 0.15 * vnoise(p * 4.3 + 13.0); }
float lum(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
// edge strength of the picture around q (for line art / outlines)
float edges(float2 q) {
    float a = lum(im(q + float2(1.6, 0.0), 0.7)); float b = lum(im(q - float2(1.6, 0.0), 0.7));
    float c = lum(im(q + float2(0.0, 1.6), 0.7)); float e = lum(im(q - float2(0.0, 1.6), 0.7));
    return clamp(length(float2(a - b, c - e)) * 4.0, 0.0, 1.0);
}

float wave(float y, float Hm) { return amp * (0.6 * sin(y / Hm * 12.566 + t * 5.0) + 0.4 * sin(y / Hm * 29.53 - t * 3.1)); }
// before the finish pass: the built picture, blurred by soft and wobbled by wobble (both 0 = the plain picture)
float3 liquid(float2 iq) {
    float2 w = float2(sin(iq.y / 38.0 + t * 3.0), cos(iq.x / 44.0 - t * 2.4)) * wobble;
    return im(iq + w, soft);
}
// N Sketch to Paint's black and white picture (end of its first pass when a finish pass is on): grey + faint pencil lines
float3 sketchGrey(float2 iq) {
    float3 a = ((soft > 0.01 || wobble > 0.01)) ? liquid(iq) : im(iq, 0.0);
    return float3(lum(a)) + float3(0.92, 0.9, 0.85) * 0.15 * ss(0.12, 0.45, edges(iq));
}
// O Hologram's held picture (end of its first pass when a finish pass is on): cyan, scanlines, a gentle flicker
float3 holoStill(float2 iq) {
    float3 a = ((soft > 0.01 || wobble > 0.01)) ? liquid(iq) : im(iq, 0.0);
    float scan = 0.65 + 0.35 * sin(iq.y * 1.9 + t * 30.0);
    float fl = 0.9 + 0.1 * hash(float2(floor(t * 30.0), floor(iq.y / 90.0)));
    return float3(0.2, 0.85, 1.0) * (0.25 + lum(a) * 1.3) * scan * fl;
}
// AH Glitch Warp's held picture (end of its first pass when a finish pass is on): in place but still glitching
float3 glitchStill(float2 iq) {
    float bh = 14.0 + 26.0 * hash(float2(floor(iq.y / 64.0), 3.0));
    float bid = floor(iq.y / bh) + 97.0 * floor(iq.y / 64.0);
    float r = hash(float2(bid, 1.0));
    float kick = step(0.93, hash(float2(bid, floor(t * 6.0) + 3.0))) * (r - 0.5) * 60.0;
    float sh = (hash(float2(bid, floor(t * 12.0))) - 0.5) * 14.0 + kick;
    float2 gq = iq + float2(sh, 0.0);
    float3 g3 = float3(im(gq + float2(7.0, 0.0), soft).r, im(gq, soft).g, im(gq - float2(7.0, 0.0), soft).b);
    float scan = 1.0 - 0.25 * step(0.5, fract(iq.y / 3.0));
    float drop = step(hash(float2(bid, floor(t * 25.0)) + 7.0), 0.04);
    return g3 * scan * mix(float3(1.0), float3(0.72, 0.5, 1.0), 0.35) * (1.0 - drop) + float3(0.75, 0.55, 1.0) * drop * 0.7;
}
// AF Frost Bloom (look from a real frost time-lapse): fine needle crystals in three directions + long warped feathers
float ridge(float2 p, float2 ax, float2 sc, float off, float pw) {
    float2 d = float2(dot(p, ax), dot(p, float2(-ax.y, ax.x)));
    return pow(1.0 - abs(2.0 * vnoise(d / sc + off) - 1.0), pw);
}
float needles(float2 p) {
    return max(max(ridge(p, float2(1.0, 0.0), float2(28.0, 3.2), 0.0, 8.0), ridge(p, float2(0.5, 0.866), float2(28.0, 3.2), 31.0, 8.0)),
               ridge(p, float2(-0.5, 0.866), float2(28.0, 3.2), 57.0, 8.0));
}
float feathers(float2 p) {
    float2 w = p + 40.0 * float2(vnoise(p / 120.0), vnoise(p / 120.0 + 5.0));
    return max(ridge(w, float2(0.766, 0.643), float2(110.0, 9.0), 83.0, 3.0), ridge(w, float2(0.766, -0.643), float2(110.0, 9.0), 97.0, 3.0));
}
// the frosted glass: bright blue-white, fine grain, feathers and needle highlights, the picture faint behind it
float3 frostTex(float2 iq, float nd, float fe) {
    float g = 0.6 * vnoise(iq / 2.3) + 0.4 * vnoise(iq / 7.0);
    float3 col = float3(0.82, 0.89, 0.97) * (0.80 + 0.22 * g + 0.25 * fe) + float3(0.25, 0.27, 0.3) * nd;
    return clamp(mix(im(iq, 2.6), col, 0.9), 0.0, 1.0);
}
// AF Frost Bloom's held picture (end of its first pass when a finish pass is on): the glass frosted over
float3 frostStill(float2 iq) { return frostTex(iq, needles(iq), feathers(iq)); }
// random freeze spots for the fill: each 110-unit cell has a seed that starts at a random time and grows outward;
// the last 15% seals any gap so the glass is fully frozen at u = 1
float freeze(float2 iq, float u) {
    float2 g = floor(iq / 110.0);
    float G = (u - 0.85) * 8.0;
    for (int oy = -1; oy <= 1; oy++) {
        for (int ox = -1; ox <= 1; ox++) {
            float2 c = g + float2(float(ox), float(oy));
            float2 sd = (c + 0.15 + 0.7 * float2(hash(c), hash(c + 19.0))) * 110.0;
            float ts = hash(c + float2(3.7, 1.3)) * 0.5;
            G = max(G, (u - ts) * 1.6 - length(iq - sd) / 110.0);
        }
    }
    return G;
}
// a = how far the frost has come here, b = how far it has cleared (u units); the edge grows along the feathers and needles
float3 frostFx(float2 iq, float a, float b, float3 empty) {
    float nd = needles(iq); float fe = feathers(iq);
    float rg = fbm(iq / 160.0);
    float xf = a - rg * 0.35 + 0.3 * fe + 0.12 * nd - 0.2;
    float xc = b - rg + 0.3 * fe - 0.2;
    float3 ice = float3(0.85, 0.93, 1.0);
    float sparkle = clamp(nd * 1.3 + fe * 0.5, 0.0, 1.0);
    // loose crystals just ahead of the frost, needles reaching out from the front, then solid frost
    float2 cell = floor(iq / 46.0); float h = hash(cell + 5.0);
    float2 cc = (cell + 0.25 + 0.5 * float2(h, hash(cell + 9.0))) * 46.0;
    float sp = step(0.75, h) * ss(16.0, 3.0, length(iq - cc)) * ss(-0.4, -0.3, xf) * (1.0 - ss(-0.2, -0.1, xf));
    float lead = ss(-0.16, 0.0, xf);
    float cov = ss(0.0, 0.04, xf);
    float3 c = mix(empty, frostTex(iq, nd, fe), cov) + ice * sparkle * max(lead * (1.0 - cov), sp);
    // clearing: the frost thins to grey first, then a crystal-edged hole opens
    float3 pic = im(iq, 0.0);
    float thin = ss(-0.3, 0.0, xc) * 0.45 * cov;
    float clr = ss(0.0, 0.04, xc);
    return mix(mix(c, pic, thin), pic, clr) + ice * sparkle * ss(-0.08, 0.0, xc) * (1.0 - clr) * 0.6 * cov;
}
"""

/** Second part of the shader: one JVM string constant is capped at 64 KB, so the source is kept in parts. */
private const val FX_SHADER_B = """
// Snow Day / Autumn Leaves / Gift Wrap fill once across both screens: the cover screen starts it (it is lit only
// ~0.25 s in an open), the inner continues from COVER_PART over its fill time, holds full, then the finish pass reveals.
const float COVER_PART = 0.35;
// ---- AQ Snow Day: snow settles over the glass in spreading patches; the finish pass plows it off (sim: mockup/holiday/sim3.py)
float3 snowTex(float2 q) {
    float g = 0.7 * vnoise(q / 3.0) + 0.3 * vnoise(q / 11.0);
    float tw = step(0.992, hash(floor(q / 2.5) + float2(0.0, floor(t * 5.0)))) * 0.35;
    return float3(0.9, 0.94, 1.0) * (0.86 + 0.14 * g) + tw;
}
// snow settles in random patches that spread and merge (the freeze growth) until the glass is covered; soft lit rim
float3 snowFill(float2 q, float u, float3 empty) {
    float xf = freeze(q, u) - fbm(q / 90.0) * 0.3 + (vnoise(q / 9.0) - 0.5) * 0.12;
    float cov = ss(-0.02, 0.04, xf);
    float3 col = snowTex(q) * (1.0 - 0.08 * exp(-max(xf, 0.0) / 0.08)) + float3(0.12 * exp(-sq(xf / 0.03)));
    return mix(empty, col, cov);
}
// u: 0 = the plow edge arriving; the bank is pushed along in front of it, the glass is clear (a little wet) behind
float3 plow(float2 q, float u, float3 under, float3 pic) {
    float e = u + (vnoise(q / 20.0) - 0.5) * 0.04;
    float clr = ss(0.185, 0.2, e);
    float k = clamp(e / 0.19, 0.0, 1.0);
    float bank = ss(0.0, 0.03, e) * (1.0 - clr);
    float lump = vnoise(q / 8.0 + float2(t * 4.0, 0.0)) * 0.6 + vnoise(q / 19.0 + float2(t * 2.0, 0.0)) * 0.4;
    float shade = 0.95 + 0.18 * (1.0 - k) - 0.30 * k * k * k + 0.15 * (lump - 0.5);
    float3 c = mix(under, snowTex(q) * shade, bank);
    float wet = clamp(1.0 - 0.2 * exp(-max(e - 0.2, 0.0) / 0.12) - 0.55 * exp(-sq((e - 0.2) / 0.018)), 0.0, 1.0);
    float crumbs = step(0.9, hash(floor(q / 16.0))) * ss(5.0, 3.0, length(fract(q / 16.0) * 16.0 - 8.0)) * (1.0 - ss(0.25, 0.5, u));
    return mix(c, mix(pic * wet, snowTex(q), crumbs * 0.9), clr);
}
// ---- AR Autumn Leaves: three layers of fall leaves drop onto the glass; the finish pass blows them away
float3 leafPal(float h) {
    float i = floor(h * 7.0);
    if (i < 1.0) return float3(0.80, 0.14, 0.06);
    if (i < 2.0) return float3(0.93, 0.42, 0.07);
    if (i < 3.0) return float3(0.97, 0.72, 0.12);
    if (i < 4.0) return float3(0.75, 0.62, 0.12);
    if (i < 5.0) return float3(0.62, 0.10, 0.06);
    if (i < 6.0) return float3(0.88, 0.52, 0.10);
    return float3(0.95, 0.85, 0.25);
}
float wrapd(float a) { return abs(mod(a + 3.14159, 6.28318) - 3.14159); }
// one leaf in its own frame (l.y down = toward the stem, the tip points up), size R, kind and colours from h.
// xyz = alpha, r (0 middle .. 1 edge, for the colour), vein. Kinds: 60% five-lobe maple, 20% three-lobe maple,
// 10% oak, 10% birch (look from his maple reference).
float3 leafShape(float2 l, float R, float h) {
    float typ = fract(h * 4.7);
    float a; float r; float vein = 0.0;
    if (typ < 0.8) {
        float cy = l.y + 0.15 * R;
        r = length(float2(l.x, cy)) / R;
        float th = atan(l.x, -cy);
        bool five = typ < 0.6;
        float env = 0.22;
        float d = wrapd(th);        env = max(env, 1.0 * (0.35 + 0.65 * exp(-d / 0.42))); vein = max(vein, exp(-sq(r * R * sin(min(d, 1.5)) / 0.9)) * step(d, 0.9));
        d = wrapd(th - 1.0);        env = max(env, 0.9 * (0.35 + 0.65 * exp(-d / 0.42)));  vein = max(vein, exp(-sq(r * R * sin(min(d, 1.5)) / 0.9)) * step(d, 0.9));
        d = wrapd(th + 1.0);        env = max(env, 0.9 * (0.35 + 0.65 * exp(-d / 0.42)));  vein = max(vein, exp(-sq(r * R * sin(min(d, 1.5)) / 0.9)) * step(d, 0.9));
        if (five) {
            d = wrapd(th - 2.0);    env = max(env, 0.55 * (0.35 + 0.65 * exp(-d / 0.42))); vein = max(vein, exp(-sq(r * R * sin(min(d, 1.5)) / 0.9)) * step(d, 0.9));
            d = wrapd(th + 2.0);    env = max(env, 0.55 * (0.35 + 0.65 * exp(-d / 0.42))); vein = max(vein, exp(-sq(r * R * sin(min(d, 1.5)) / 0.9)) * step(d, 0.9));
        }
        env *= (1.0 - 0.42 * exp(-sq(wrapd(th - 0.5) / 0.09))) * (1.0 - 0.42 * exp(-sq(wrapd(th + 0.5) / 0.09)));
        if (five) {
            env *= (1.0 - 0.4 * exp(-sq(wrapd(th - 1.5) / 0.09))) * (1.0 - 0.4 * exp(-sq(wrapd(th + 1.5) / 0.09)));
            env *= (1.0 - 0.45 * exp(-sq(wrapd(th - 2.6) / 0.09))) * (1.0 - 0.45 * exp(-sq(wrapd(th + 2.6) / 0.09)));
        } else {
            env *= (1.0 - 0.6 * exp(-sq(wrapd(th - 1.9) / 0.09))) * (1.0 - 0.6 * exp(-sq(wrapd(th + 1.9) / 0.09)));
        }
        float pts = abs(fract(th * 15.0 / 6.28318 + 0.5) * 2.0 - 1.0);
        float ser = abs(fract(th * 48.0 / 6.28318) * 2.0 - 1.0);
        a = ss(0.015, -0.015, r - env * (1.0 - 0.16 * pts * pts) * (1.0 - 0.035 * ser));
    } else {
        bool oak = fract(h * 2.3) < 0.5;
        float tt = (l.y / R + 0.25) / 0.78;
        float prof = sqrt(clamp(1.0 - tt * tt, 0.0, 1.0)) * (1.0 - 0.45 * ss(0.0, -1.0, tt));
        float w = oak ? 0.5 * R * prof * (0.62 + 0.38 * abs(cos(tt * 3.14159 * 2.6)))
                      : 0.36 * R * prof * (1.0 - 0.08 * abs(fract(tt * 16.0) * 2.0 - 1.0));
        a = ss(1.0, -1.0, abs(l.x) - w) * step(abs(tt), 1.0);
        r = clamp(abs(l.x) / max(w, 0.001) * 0.7 + abs(tt) * 0.3, 0.0, 1.2);
        vein = exp(-sq(l.x)) + exp(-sq((fract((l.y - abs(l.x) * 0.8) / 16.0) - 0.5) * 16.0)) * step(abs(l.x), w * 0.85) * 0.6;
    }
    float stem = ss(1.1, 0.5, abs(l.x - 0.04 * sq(l.y - 0.15 * R) / R)) * step(0.1 * R, l.y) * step(l.y, 0.85 * R);
    return float3(max(a, stem), r, vein);
}
float3 leafColor(float2 l, float h, float r, float vein) {
    float k = ss(0.15, 1.0, r + 0.35 * (vnoise(float2(l.x / 18.0 + h * 20.0, l.y / 18.0)) - 0.5));
    float3 col = mix(leafPal(fract(h * 11.7)), leafPal(fract(h * 5.9)), k);
    col *= 0.8 + 0.3 * (vnoise(float2(l.x / 6.0 + h * 50.0, l.y / 6.0)) * 0.6 + vnoise(float2(l.x / 2.5, l.y / 2.5 + h * 30.0)) * 0.4);
    return col * (1.0 - 0.25 * vein) + 0.12 * vein * float3(1.0, 0.9, 0.5);
}
float3 litter(float2 q) {
    float n = fbm(q / 26.0);
    return mix(float3(0.42, 0.2, 0.07), float3(0.72, 0.4, 0.11), n) * (0.7 + 0.3 * vnoise(q / 6.0));
}
// a leaf's own u (so it moves as one piece): along the sweep, or for finish C (out from the middle) from the circle
float leafU(float2 ctr, float2 q, float u0, float dir) {
    if (fin >= 0.0 && finMode == 3) {
        float2 mid = res / S * 0.5;
        return clamp(((length(mid) + amp + tail) * fin - length(ctr - mid)) / tail, 0.0, 1.0);
    }
    return clamp(u0 + dir * (ctr.x - q.x) / tail, 0.0, 1.0);
}
// AR Autumn Leaves (sim: mockup/holiday/leafsim). Landing: fi from (uc * kin + bin); blowing off: fo from (uc * kout + bout).
// kin 0 / bin 9 = all landed, kout 0 / bout -9 = none blown. uc = each leaf's own u, so a leaf moves as one piece.
// Under the four falling layers: a carpet of small leaves on leaf litter, so the glass ends fully covered.
float3 leaves(float2 q, float u, float u0, float dir, float kin, float bin, float kout, float bout, float3 empty, float3 pic, float Hm) {
    // the carpet + litter (one leaf per small cell, no flight)
    float bIn = ss(0.45, 0.7, u * kin + bin + 0.12 * (vnoise(q / 40.0) - 0.5));
    float bOut = ss(0.6, 0.85, u * kout + bout);
    float3 c = mix(mix(empty, litter(q), bIn), pic, bOut);
    {
        float cs = 54.0; float2 g = floor(q / cs); float h = hash(g + float2(3.0, 5.0));
        float2 ctr = (g + 0.5) * cs;
        float uc = leafU(ctr, q, u0, dir);
        float v = ss(0.55 + 0.25 * fract(h * 3.1), 0.7 + 0.25 * fract(h * 3.1), uc * kin + bin) * (1.0 - ss(0.5, 0.75, uc * kout + bout));
        if (v > 0.002) {
            float ang = h * 6.283; float ca = cos(ang); float sa = sin(ang);
            float2 s0 = q - ctr; float2 l = float2(s0.x * ca + s0.y * sa, -s0.x * sa + s0.y * ca);
            float3 lf = leafShape(l, cs * 0.5, h);
            c = mix(c, leafColor(l, h, lf.y, lf.z), lf.x * v);
        }
    }
    float CS = 130.0;
    for (int L = 0; L < 4; L++) {
        float fl = float(L);
        float2 o = float2(29.0, 41.0) * fl;
        float2 g = floor((q + o) / CS);
        float accA = 0.0; float3 accC = float3(0.0); float sh = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float2 cc = g + float2(float(i), float(j));
                float h = hash(cc + float2(17.0, 29.0) * fl);
                float2 ctr = (cc + 0.3 + 0.4 * float2(fract(h * 13.1), fract(h * 7.3))) * CS - o;
                float uc = leafU(ctr, q, u0, dir);
                // lower leaves land a little earlier: the pile builds up from the bottom
                float ts = min(0.34 + 0.1 * fl + fract(h * 3.7) * 0.38 - 0.12 * (ctr.y / Hm - 0.5), 0.95);
                float to = 0.62 - 0.17 * fl + fract(h * 3.7) * 0.15;
                float fi = ss(ts - 0.3, ts, uc * kin + bin);
                float fo = ss(to, to + 0.3, uc * kout + bout);
                float vis = ss(0.0, 0.15, fi) * (1.0 - ss(0.55, 1.0, fo));
                if (vis > 0.002) {
                    float ai = 1.0 - fi;
                    float air = ai + fo;
                    float ph = t * 2.6 + h * 6.283;
                    // falling: the classic leaf swing (side to side, tilting with it), then a gust lifts it off up and away
                    float2 pos = ctr + ai * float2(0.14 * CS * sin(ph), -0.45 * CS) + fo * float2(-0.5, -0.4) * CS;
                    float ang = h * 6.283 + ai * 0.55 * cos(ph) + ai * (fract(h * 5.3) - 0.5) * 1.5 + fo * (fract(h * 5.3) - 0.5) * 8.0;
                    float sc = 1.0 + 0.3 * ai + 0.4 * fo;
                    float flip = 1.0 - ai * 0.5 * (0.5 + 0.5 * sin(ph * 2.0 + 1.0)) - fo * 0.6 * (0.5 + 0.5 * sin(t * 6.0 + h * 7.0));
                    float R = 0.5 * CS * (0.85 + 0.3 * fract(h * 2.9));
                    float ca = cos(ang); float sa = sin(ang);
                    if (L >= 2) {
                        float2 s0 = (q - pos - float2(3.0 + 12.0 * air, 4.0 + 14.0 * air)) / sc;
                        if (length(s0) < R * 1.9)
                            sh = max(sh, leafShape(float2((s0.x * ca + s0.y * sa) / max(flip, 0.2), -s0.x * sa + s0.y * ca), R, h).x * vis * 0.45);
                    }
                    float2 s1 = (q - pos) / sc;
                    if (length(s1) < R * 1.9) {
                        float2 l = float2((s1.x * ca + s1.y * sa) / max(flip, 0.2), -s1.x * sa + s1.y * ca);
                        float3 lf = leafShape(l, R, h);
                        float a = lf.x * vis;
                        if (a > accA) { accC = leafColor(l, h, lf.y, lf.z) * (0.75 + 0.25 * flip); accA = a; }
                    }
                }
            }
        }
        c = mix(c * (1.0 - sh), accC, accA);
    }
    return clamp(c, 0.0, 1.0);
}
// ---- AS Gift Wrap (sim: mockup/holiday/xmas_sim.py): red paper, gold pinstripes and stars, ribbon cross and bow
float3 giftPaper(float2 q) {
    float d = (q.x + q.y) / 46.0;
    float stripe = ss(0.42, 0.46, abs(fract(d) - 0.5));
    float3 c = mix(float3(0.72, 0.05, 0.08), float3(0.55, 0.03, 0.06), stripe);
    c = mix(c, float3(0.95, 0.78, 0.32), exp(-sq((fract(d) - 0.5) * 46.0 / 1.6)) * 0.9);
    float2 cl = floor(q / 58.0); float h = hash(cl);
    float2 dd = q - (cl + 0.5) * 58.0; float r = length(dd); float a = atan(dd.y, dd.x);
    float star = step(r, 11.0) * ss(0.5, 0.15, abs(sin(a * 3.0)) * r / 11.0 + 0.1 * r / 11.0) * step(0.35, h);
    c = mix(c, float3(0.98, 0.96, 0.9), star * 0.9);
    return c * (0.9 + 0.15 * sin((q.x * 0.6 - q.y) / 240.0 + 1.0));
}
// w = how wrapped (0 nothing .. 1 done): 0-0.55 the two flaps fold in from the sides and meet at the ribbon,
// 0.55-0.8 the ribbon runs out from the knot, 0.8-0.95 the bow pops on. Under it: the home screen.
float3 giftWrap(float2 q, float w, float3 under, float Wm, float Hm) {
    float rx = 0.62 * Wm; float ry = 0.42 * Hm;
    float fl = ss(0.0, 0.55, w);
    float xl = -30.0 + (rx + 30.0) * fl; float xr = Wm + 30.0 - (Wm - rx + 30.0) * fl;
    float inL = step(q.x, xl); float inR = step(xr, q.x);
    float edge = min(abs(q.x - xl) + (1.0 - inL) * 999.0, abs(q.x - xr) + (1.0 - inR) * 999.0);
    float3 paper = giftPaper(q) * (1.0 - 0.35 * exp(-edge / 18.0) * (1.0 - step(0.999, fl))) + float3(0.25 * exp(-sq(edge / 2.0)));
    float3 c = mix(under, paper, max(inL, inR));
    // drop shadow of each flap edge onto the screen underneath
    c *= 1.0 - 0.4 * (1.0 - max(inL, inR)) * (exp(-max(q.x - xl, 0.0) / 14.0) * step(xl, q.x) + exp(-max(xr - q.x, 0.0) / 14.0) * step(q.x, xr)) * (1.0 - step(0.999, fl));
    float3 rib = float3(0.97, 0.82, 0.30);
    float g = ss(0.55, 0.8, w);
    float rv = step(abs(q.x - rx), 34.0) * step(abs(q.y - ry), g * Hm);
    float rh = step(abs(q.y - ry), 34.0) * step(abs(q.x - rx), g * Wm);
    c = mix(c, rib * (0.8 + 0.3 * cos((q.x - rx) / 34.0 * 1.5708)), rv);
    c = mix(c, rib * (0.8 + 0.3 * cos((q.y - ry) / 34.0 * 1.5708)), rh * (1.0 - rv));
    float b = ss(0.8, 0.9, w); b = b * (1.0 + 0.25 * sin(3.14159 * ss(0.8, 0.95, w)));   // pops with a little overshoot
    if (b > 0.01) {
        for (int k = 0; k < 2; k++) {
            float sd = (k == 0) ? -1.0 : 1.0;
            float2 l = float2((q.x - rx - sd * 62.0 * b) / (70.0 * b), (q.y - ry + 8.0 * b) / (42.0 * b));
            float loop = step(dot(l, l), 1.0) * step(1.0, l.x * l.x / 0.36 + l.y * l.y / 0.3);
            c = mix(c, rib * (0.75 + 0.3 * (1.0 - abs(l.y))), loop);
            float tl = step(abs((q.x - rx) - sd * (q.y - ry) * 0.45), 16.0 * b) * step(ry, q.y) * step(q.y, ry + 120.0 * b);
            c = mix(c, rib * 0.85, tl);
        }
        c = mix(c, rib * 1.05, step(sq((q.x - rx) / (26.0 * b)) + sq((q.y - ry) / (22.0 * b)), 1.0));
    }
    return clamp(c, 0.0, 1.0);
}
// the reveal: the paper tears open along a ragged edge with white paper fibres, curling and shading as it goes
float3 giftTear(float2 q, float u, float3 cover, float3 pic) {
    float e = u + (fbm(q / 30.0) - 0.5) * 0.12 + (vnoise(q / 6.0) - 0.5) * 0.03;
    float gone = ss(0.06, 0.07, e);
    float curl = ss(0.0, 0.06, e) * (1.0 - gone);
    float3 c = mix(cover * (1.0 - 0.35 * curl), pic * (1.0 - exp(-max(e - 0.07, 0.0) / 0.02) * 0.4), gone);
    return mix(c, float3(0.96, 0.94, 0.9), exp(-sq((e - 0.065) / 0.008)) * 0.95);
}
// L Pixel Assemble's blocks: sz = block size, ul = this block's progress, bev = bevel strength, glow on the pop
float3 pixelBlocks(float2 iq, float sz, float ul, float bev, float3 empty) {
    float2 f = fract(iq / sz);
    float2 cc = (floor(iq / sz) + 0.5) * sz;
    float lvl = clamp(log2(sz * S) * 0.5 - 0.6, 0.0, 3.0);
    float pop = ss(0.0, 0.08, ul);
    float g = (1.0 - pop) * 0.45;
    float inside = (f.x > g * 0.5 && f.x < 1.0 - g * 0.5 && f.y > g * 0.5 && f.y < 1.0 - g * 0.5) ? 1.0 : 0.0;
    float bevel = bev * 0.35 * (step(f.x, 0.08) + step(f.y, 0.08)) - bev * 0.25 * (step(0.92, f.x) + step(0.92, f.y));
    float3 blk = im(cc, lvl) * (1.0 + bevel);
    return mix(empty, blk, inside * pop) + float3(0.5, 0.9, 1.0) * inside * pop * (1.0 - ss(0.0, 0.3, ul)) * 0.5;
}
// L Pixel Assemble's held picture (end of its first pass when a finish pass is on): 12-unit blocks, light bevel
float3 pixelStill(float2 iq) { return pixelBlocks(iq, 12.0, 1.0, 0.34, float3(0.0)); }
// P Crystal: ice cells grow (grow 0..1) and melt (melt 0..1); glint = the growing rim
float3 crystalFx(float2 iq, float u, int phase, float3 empty) {
    float cs = 44.0;
    float2 g = floor(iq / cs); float2 f = fract(iq / cs);
    float F1 = 8.0; float F2 = 8.0; float2 best = float2(0.0); float2 bo = float2(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 o = float2(float(i), float(j));
            float2 sp = o + float2(hash(g + o), hash(g + o + 19.0));
            float2 rr = sp - f;
            float dd = dot(rr, rr);
            if (dd < F1) { F2 = F1; F1 = dd; best = g + sp; bo = rr; } else if (dd < F2) { F2 = dd; }
        }
    }
    float r = hash(floor(best * 7.0));
    float ul = ss(0.3 * r, 0.3 * r + 0.7, u);
    // phase 0: grow then melt in one pass; 1: grow only (held frozen); 2: frozen, then melt
    float grow = (phase == 2) ? 1.0 : ss(0.0, (phase == 1) ? 0.7 : 0.45, ul);
    float melt = (phase == 0) ? ss(0.65, 1.0, ul) : (phase == 2) ? ss(0.1, 0.9, ul) : 0.0;
    float inC = step(sqrt(F1), grow * 1.1);
    float facet = 1.0 + 0.35 * dot(normalize(bo + 0.0001), float2(0.7, -0.7));
    float3 ice = mix(im(best * cs, 2.6), float3(0.75, 0.9, 1.0), 0.35) * facet;
    float edge = 1.0 - ss(0.0, 0.06, sqrt(F2) - sqrt(F1));
    float3 c = mix(empty, mix(ice + float3(0.9, 0.97, 1.0) * edge * 0.8, im(iq, 0.0), melt), inC)
      + float3(0.8, 0.95, 1.0) * exp(-sq((sqrt(F1) - grow * 1.1) / 0.03)) * (1.0 - melt) * 0.7 * (1.0 - step(0.999, grow));
    if (phase == 2) c += float3(0.85, 0.95, 1.0) * exp(-sq((melt - 0.5) / 0.15)) * 0.25;
    return c;
}
float3 crystalStill(float2 iq) { return crystalFx(iq, 1.0, 1, float3(0.0)); }
// what an effect builds out of: black in the fill; in the finish pass the finished picture, dimmed (noise-broken, no line)
float3 emptyC(float gFin, float2 iq, float u) {
    if (gFin < 0.5) return float3(0.0);
    float3 a = ((soft > 0.01 || wobble > 0.01)) ? liquid(iq) : im(iq, 0.0);
    float n = vnoise(iq / 45.0) * 0.6 + vnoise(iq / 14.0) * 0.4;
    float x = u * 1.3 - n * 0.3;
    return a * mix(float3(1.0), float3(0.30, 0.20, 0.50), depth * ss(0.0, 0.2, x));
}
"""

/** Third part of the shader: the effect bodies and main(). */
private const val FX_SHADER_C = """
// BUILD effects: u = how built the picture is here (0 at the front, 1 = finished, tail units behind it).
// arriving (dir 1): the zone is d > 0; leaving (cover, dir -1) the same build runs backwards = the picture comes apart.
float3 effect(float2 q, float2 iq, float u, float fr, float dir, float off, float d, float pg, float gFin, float Wm, float Hm, float base) {
    float3 c = float3(0.0);
        if (fx == 0) {                              // K Stardust: glowing dots stream in from the front and swell into the picture
            float cell = 9.0;
            float rowr = hash(float2(floor(q.y / cell), 5.0));
            float fly = 260.0 * (0.5 + rowr);
            // invert the flight (each dot is shifted toward the front until it lands): fixed-point on x
            float xt = q.x;
            for (int k = 0; k < 4; k++) {
                float ut = clamp(dir * (xt - fr) / tail, 0.0, 1.0);
                float m = 1.0 - ss(0.0, 0.75, ut);
                xt = q.x + dir * m * m * fly;
            }
            float ut = clamp(dir * (xt - fr) / tail, 0.0, 1.0);
            float2 tq = float2(xt - off, q.y + sin(xt / 37.0 + t * 3.0) * 10.0 * (1.0 - ss(0.0, 0.75, ut)));
            float2 cid = floor(tq / cell);
            float r = hash(cid);
            float ul = ss(0.25 * r, 0.25 * r + 0.75, ut);
            float2 f = fract(tq / cell) - 0.5;
            float rad = mix(0.10, 0.75, ul);
            float dotm = (ul > 0.97) ? 1.0 : 1.0 - ss(rad - 0.08, rad, length(f));
            float alive = ss(0.0, 0.06, ut);
            float hot = (1.0 - ul);
            float tw = step(0.9, hash(cid + floor(t * 18.0))) * hot;
            c = mix(emptyC(gFin, float2(q.x - off, q.y), u), im(ul > 0.97 ? float2(q.x - off, q.y) : (cid + 0.5) * cell, ul > 0.97 ? 0.0 : 0.8), dotm * alive);
            c += float3(1.0, 0.85, 0.6) * dotm * alive * (hot * 0.9 + tw);
        } else if (fx == 1) {                       // L Pixel Assemble: big blocks drop in, split finer and finer, sharpen
            float rb = hash(floor(iq / 48.0));
            float ul = clamp(u * 1.25 - rb * 0.25, 0.0, 1.0);
            // with a finish pass on, the first pass stops at 12-unit blocks; the finish pass splits them down and sharpens
            if (finMode > 0 && arriving > 0.5) {
                if (gFin < 0.5) {
                    float sz = 48.0 / exp2(floor(min(ul * 3.0, 2.0)));
                    c = (u > 0.995) ? pixelStill(iq) : pixelBlocks(iq, sz, ul, max(1.0 - ul, 0.34), emptyC(gFin, iq, u));
                } else {
                    float sz = 12.0 / exp2(floor(min(ul * 3.0, 2.0)));
                    c = mix(pixelBlocks(iq, sz, 1.0, 0.34 * (1.0 - ul), float3(0.0)), im(iq, 0.0), ss(0.8, 1.0, ul))
                      + float3(0.5, 0.9, 1.0) * 0.12 * (1.0 - ss(0.0, 0.08, fract(ul * 3.0))) * (1.0 - ss(0.66, 0.8, ul));
                }
            } else {
                float sz = 48.0 / exp2(floor(min(ul * 5.0, 4.0)));
                c = mix(pixelBlocks(iq, sz, ul, 1.0 - ul, emptyC(gFin, iq, u)), im(iq, 0.0), ss(0.88, 1.0, ul) * ss(0.0, 0.08, ul));
            }
        } else if (fx == 2) {                       // M Sandstorm: grains blow in on the wind and settle into the picture
            float gq = 1.3;
            float n = hash(floor(iq / gq));
            float settle = u * 1.15;
            float3 sand = float3(1.0, 0.82, 0.55);
            if (n < settle) {
                c = mix(im(iq, 0.0), lum(im(iq, 0.0)) * sand * 1.3, 0.6 * (1.0 - ss(0.5, 1.0, u)));
            } else {
                // a grain still in the air: look upwind for one that will land further on
                float wind = 120.0 + 160.0 * hash(float2(floor(q.y / 3.0), 9.0));
                float2 sp = q + float2(dir * wind * (1.0 - u) + 30.0 * sin(q.y / 50.0 + t * 4.0), 18.0 * sin(q.x / 41.0 - t * 5.0));
                float us = clamp(dir * (sp.x - fr) / tail, 0.0, 1.0);
                float2 si = float2(sp.x - off, sp.y);
                float ns = hash(floor(si / gq));
                float flying = step(ns, us * 1.15 + 0.25) * step(us * 1.15, ns) * step(0.02, u + us);
                c = mix(emptyC(gFin, iq, u), im(si, 0.5) * 0.6 + sand * 0.6, flying * 0.9);
            }
        } else if (fx == 3) {                       // N Sketch to Paint: pencil lines draw it, then colour washes in
            float e = edges(iq);
            float nz = 0.6 * vnoise(iq / 28.0) + 0.4 * vnoise(iq / 9.0);
            float draw = ss(nz * 0.3, nz * 0.3 + 0.12, u);
            float lines = ss(0.12, 0.45, e) * draw * (1.0 - ss(0.75, 1.0, u) * 0.85);
            float wash = ss(nz - 0.12, nz + 0.12, (u - 0.3) / 0.65);
            float3 paper = mix(emptyC(gFin, iq, u), float3(0.07, 0.065, 0.06), draw);
            float3 paint = im(iq, 1.6 * (1.0 - ss(0.7, 1.0, u)));
            paint = mix(paint, im(iq, 0.0), ss(0.8, 1.0, u));
            float rim = exp(-sq((wash - 0.5) / 0.18)) * (1.0 - u) * 0.25;
            // with a finish pass on, the first pass paints black and white; the colour comes in with the finish
            if (finMode > 0 && arriving > 0.5) {
                if (gFin < 0.5) {
                    c = mix(paper, float3(lum(paint)) * (1.0 - rim), wash) + float3(0.92, 0.9, 0.85) * lines;
                } else {
                    float wash2 = ss(nz - 0.12, nz + 0.12, u / 0.75);
                    float rim2 = exp(-sq((wash2 - 0.5) / 0.18)) * (1.0 - u) * 0.2;
                    c = mix(sketchGrey(iq), im(iq, 0.0) * (1.0 - rim2), wash2);
                }
            } else {
                c = mix(paper, paint * (1.0 - rim), wash) + float3(0.92, 0.9, 0.85) * lines;
            }
        } else if (fx == 4) {                       // O Hologram: outline flickers on, scanlines fill it in cyan, then true colour
            float rowh = 7.0;
            float rr = hash(float2(floor(iq.y / rowh), 2.0));
            float ur = clamp(u * 1.5 - rr * 0.5, 0.0, 1.0);
            float fl = 0.75 + 0.25 * hash(float2(floor(t * 30.0), floor(q.y / 90.0)));
            float e = edges(iq);
            float outline = ss(0.1, 0.4, e) * ss(0.0, 0.08, u) * (1.0 - ss(0.7, 1.0, u)) * fl;
            float scan = 0.65 + 0.35 * sin(q.y * 1.9 + t * 30.0);
            float fill = ss(0.15, 0.55, ur);
            float real = ss(0.75, 1.0, ur);
            float sh = 6.0 * (1.0 - real);
            float3 img = float3(im(iq + float2(sh, 0.0), 0.0).r, im(iq, 0.0).g, im(iq - float2(sh, 0.0), 0.0).b);
            float3 holo = float3(0.2, 0.85, 1.0) * (0.25 + lum(img) * 1.3) * scan * fl;
            // with a finish pass on, the first pass leaves a hologram; the finish pass makes it real, row by row
            if (finMode > 0 && arriving > 0.5) {
                if (gFin < 0.5) {
                    c = mix(emptyC(gFin, iq, u), mix(holo, holoStill(iq), ss(0.6, 1.0, u)), fill)
                      + float3(0.3, 0.95, 1.0) * outline + float3(0.5, 1.0, 1.0) * exp(-sq((ur - 0.15) / 0.05)) * 0.6;
                } else {
                    float real2 = ss(0.2, 0.9, ur);
                    float sh2 = 6.0 * (1.0 - real2);
                    float3 img2 = float3(im(iq + float2(sh2, 0.0), 0.0).r, im(iq, 0.0).g, im(iq - float2(sh2, 0.0), 0.0).b);
                    c = mix(holoStill(iq), img2, real2) + float3(0.5, 1.0, 1.0) * exp(-sq((ur - 0.2) / 0.06)) * 0.5;
                }
            } else {
                c = mix(mix(emptyC(gFin, iq, u), holo, fill), img, real) + float3(0.3, 0.95, 1.0) * outline
                  + float3(0.5, 1.0, 1.0) * exp(-sq((ur - 0.15) / 0.05)) * 0.6;
            }
        } else if (fx == 5) {                       // P Crystal: ice crystals grow in each cell, glint, then melt to the picture
            // with a finish pass on, the first pass leaves it frozen solid; the finish pass melts it cell by cell
            if (finMode > 0 && arriving > 0.5) c = (gFin < 0.5) ? ((u > 0.995) ? crystalStill(iq) : crystalFx(iq, u, 1, emptyC(gFin, iq, u))) : crystalFx(iq, u, 2, float3(0.0));
            else c = crystalFx(iq, u, 0, emptyC(gFin, iq, u));
        } else if (fx == 6) {                       // Q Raindrops: drops land and ripple the picture into place
            float cs = 110.0;
            float2 g = floor(iq / cs);
            float have = 0.0; float2 disp = float2(0.0); float ring = 0.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 o = g + float2(float(i), float(j));
                    float2 ctr = (o + 0.2 + 0.6 * float2(hash(o), hash(o + 3.0))) * cs;
                    float uc = clamp(dir * (ctr.x + off - fr) / tail, 0.0, 1.0);
                    float rc = hash(o + 11.0);
                    float ud = ss(0.35 * rc, 0.35 * rc + 0.6, uc);
                    float rad = ud * 175.0;
                    float2 v = iq - ctr; float dl = length(v);
                    have = max(have, step(dl, rad) * step(0.001, ud));
                    float rg = exp(-sq((dl - rad) / 7.0)) * (1.0 - ss(0.7, 1.0, ud)) * step(0.001, ud);
                    ring = max(ring, rg);
                    disp += (v / (dl + 0.001)) * rg * 12.0;
                }
            }
            c = mix(emptyC(gFin, iq, u), im(iq - disp, 0.0) * (1.0 - 0.2 * ring), have) + float3(0.85, 0.95, 1.0) * ring * 0.55;
        } else if (fx == 7) {                       // R Eclipse Bloom: purple cracks light up, cells bloom outward from them
            float2 g = floor(iq / 60.0); float2 f = fract(iq / 60.0);
            float F1 = 8.0; float F2 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 o = float2(float(i), float(j));
                    float2 sp = o + float2(hash(g + o), hash(g + o + 19.0));
                    float2 rr = sp - f;
                    float dd = dot(rr, rr);
                    if (dd < F1) { F2 = F1; F1 = dd; best = g + sp; } else if (dd < F2) { F2 = dd; }
                }
            }
            float e = sqrt(F2) - sqrt(F1);
            float r = hash(floor(best * 5.0));
            float ul = ss(0.25 * r, 0.25 * r + 0.75, u);
            float crack = (1.0 - ss(0.0, 0.05, e)) * ss(0.0, 0.15, u) * (1.0 - ss(0.85, 1.0, u));
            float fillR = ul * 0.75;
            float filled = (ul > 0.97) ? 1.0 : 1.0 - ss(fillR - 0.03, fillR, e);
            float bloom = exp(-sq((e - fillR) / 0.035)) * (1.0 - ul) * step(0.02, ul);
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - filled) * 0.8;
            c = im(iq, 0.0) * filled + float3(0.62, 0.32, 1.0) * crack * 0.95
              + float3(0.8, 0.55, 1.0) * bloom * 0.9 + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 8) {                       // S Corona: each spot is a little total eclipse, the moon slides off the picture
            float cs = 72.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs) - 0.5;
            float r = hash(g + 2.0);
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float appear = ss(0.0, 0.25, ul);
            float2 mc = normalize(float2(0.8 * dir, -0.6)) * ss(0.35, 0.9, ul) * 1.3;
            float md = length(f - mc);
            float R = mix(0.38, 0.75, ss(0.45, 1.0, ul));
            float disc = 1.0 - ss(R - 0.03, R, length(f));
            float moon = (1.0 - ss(0.36, 0.39, md)) * (1.0 - ss(0.9, 1.0, ul));
            float fade = 1.0 - ss(0.75, 1.0, ul);
            float corona = (exp(-sq((md - 0.39) / 0.025)) * 0.9 + exp(-max(md - 0.39, 0.0) / 0.09) * 0.35 * step(0.39, md)) * appear * fade;
            float bead = step(0.82, hash(floor(float2(atan(f.y - mc.y, f.x - mc.x) * 4.0, floor(t * 10.0)) + g))) * exp(-sq((md - 0.39) / 0.02)) * appear * fade;
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - disc * appear) * 0.7;
            c = (ul >= 1.0) ? im(iq, 0.0) : mix(emptyC(gFin, iq, u), im(iq, 0.0) * (1.0 - moon), disc * appear)
              + float3(0.8, 0.55, 1.0) * corona + float3(1.0, 0.95, 1.0) * bead + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 9) {                       // T Moon Shards: purple glass shards drop into place, glint, then clear
            float cs = 70.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs);
            float F1 = 8.0; float F2 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = dot(sp - f, sp - f);
                    if (dd < F1) { F2 = F1; F1 = dd; best = g + sp; } else if (dd < F2) { F2 = dd; }
                }
            }
            float e = sqrt(F2) - sqrt(F1);
            float r = hash(floor(best * 7.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float pop = ss(0.0, 0.12, ul);
            float k = 1.0 - ss(0.0, 0.8, ul);
            float a = r * 6.283;
            float2 o = float2(cos(a), sin(a)) * k * k * 70.0 + float2(dir * k * k * 120.0, 0.0);
            float gap = step(k * 0.08 + 0.004, e);
            float3 glass = im(iq + o, 1.4 * k) * float3(0.72, 0.52, 1.0) * 1.15;
            float2 fl = fract(iq / cs);
            float glint = exp(-sq((fl.x + fl.y - (ul * 3.0 - 0.5)) / 0.12)) * (1.0 - ss(0.6, 0.9, ul));
            float edge = exp(-e / 0.02) * (1.0 - ss(0.6, 1.0, ul)) * pop;
            float3 sh = mix(emptyC(gFin, iq, u), mix(glass, im(iq, 0.0), ss(0.6, 1.0, ul)), gap * pop);
            c = (ul >= 1.0) ? im(iq, 0.0) : sh + float3(0.95, 0.85, 1.0) * glint * gap * pop * 0.6 + float3(0.65, 0.35, 1.0) * edge * 0.9;
        } else if (fx == 10) {                      // U Constellation: stars light up, purple lines join them, the picture grows from each star
            float cs = 90.0;
            float2 pc = iq / cs; float2 g = floor(pc);
            float F1 = 8.0; float ln = 8.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 o = g + float2(float(i), float(j));
                    float2 s0 = seed(o);
                    F1 = min(F1, length(pc - s0));
                    ln = min(ln, segd(pc, s0, seed(o + float2(1.0, 0.0))));
                    ln = min(ln, segd(pc, s0, seed(o + float2(0.0, 1.0))));
                    if (hash(o + 5.0) > 0.5) { ln = min(ln, segd(pc, s0, seed(o + float2(1.0, 1.0)))); }
                }
            }
            float tw = 0.75 + 0.25 * sin(t * 9.0 + hash(g) * 30.0);
            float stars = exp(-sq(F1 * cs / 3.5)) * ss(0.0, 0.15, u) * tw + exp(-F1 * cs / 14.0) * 0.35 * ss(0.0, 0.15, u);
            float lines = exp(-sq(ln * cs / 1.6)) * ss(0.12, 0.35, u) * (1.0 - ss(0.75, 1.0, u));
            float fillR = ss(0.35, 1.0, u) * 1.05;
            float filled = (u > 0.995) ? 1.0 : 1.0 - ss(fillR - 0.04, fillR, F1);
            float rim = exp(-sq((F1 - fillR) / 0.03)) * step(0.01, fillR) * (1.0 - ss(0.85, 1.0, u));
            c = mix(emptyC(gFin, iq, u), im(iq, 0.0), filled) + (float3(0.95, 0.9, 1.0) * stars + float3(0.62, 0.36, 1.0) * lines * 0.9
              + float3(0.78, 0.5, 1.0) * rim * 0.8) * (1.0 - filled * ss(0.85, 1.0, u));
        } else if (fx == 11) {                      // V Nebula Burn: purple nebula clouds drift in, then burn away to the picture
            float n = fbm(iq / 90.0 + float2(t * 0.15, -t * 0.08));
            float k = u * 1.35 - n * 0.35;
            float shown = ss(0.5, 0.53, k);
            float burn = exp(-sq((k - 0.515) / 0.022)) * (1.0 - ss(0.9, 1.0, u));
            float dens = ss(0.25, 0.85, n);
            float3 neb = mix(float3(0.10, 0.04, 0.25), float3(0.65, 0.3, 1.0), dens) * dens * ss(0.0, 0.25, u)
                       + float3(0.3, 0.6, 1.0) * ss(0.7, 0.95, fbm(iq / 40.0 - t * 0.1)) * 0.25 * ss(0.0, 0.25, u);
            float star = step(0.995, hash(floor(q / 3.0))) * ss(0.0, 0.2, u) * 0.8;
            c = (u > 0.995) ? im(iq, 0.0) : im(iq, 0.0) * shown + (emptyC(gFin, iq, u) * (1.0 - 0.7 * dens) + neb + float3(0.9, 0.9, 1.0) * star) * (1.0 - shown)
              + mix(float3(0.85, 0.35, 1.0), float3(1.0, 0.9, 1.0), burn) * burn * 1.1;
        } else if (fx == 12) {                      // W Blood Moon: the picture rises out of the dark in eclipse red, turns purple, then true colour
            float3 pic = im(iq, 2.4 * (1.0 - ss(0.2, 0.8, u)));
            float l = lum(pic);
            float a = ss(0.0, 0.3, u);
            float3 red = float3(0.75, 0.10, 0.12) * l * 1.7;
            float3 pur = float3(0.62, 0.32, 1.0) * l * 1.5;
            float3 m = mix(emptyC(gFin, iq, u), mix(red, pur, ss(0.3, 0.65, u)), mix(a, 1.0, ss(0.3, 0.65, u)));
            float shine = exp(-sq((u - 0.66 + 0.03 * sin(q.y / 30.0 + t * 4.0)) / 0.025));
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - a) * 0.8;
            c = (u > 0.995) ? im(iq, 0.0) : mix(m, im(iq, 0.0), ss(0.62, 1.0, u)) + float3(1.0, 0.85, 1.0) * shine * 0.7 * (1.0 - gFin)
              + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 13) {                      // X Solar Flare: a plasma arc rides the front, the picture is welded in behind it, sparks drop off
            float n = fbm(float2(q.y / 40.0 - t * 2.0, q.x / 60.0 + t * 0.7));
            float k = u + (n - 0.5) * 0.14;
            float arc = exp(-sq((k - 0.06) / 0.028));
            float shown = ss(0.05, 0.11, k);
            float heat = 1.0 - ss(0.1, 0.75, u);
            float3 pic = im(iq, 0.0);
            float3 hot = mix(pic, float3(1.0, 0.55, 0.15) * (0.45 + lum(pic)), heat * 0.85) + float3(1.0, 0.75, 0.35) * heat * heat * 0.45;
            float2 sg = float2(q.x + sin(q.y / 23.0) * 6.0, q.y - t * 280.0);
            float2 cid = floor(sg / 13.0); float2 f = fract(sg / 13.0) - 0.5;
            float spark = step(0.93, hash(cid)) * exp(-dot(f, f) / 0.015) * (1.0 - ss(0.05, 0.45, u)) * ss(0.0, 0.03, u);
            float corona = exp(-max(-k, 0.0) * 18.0) * (1.0 - ss(0.0, 0.1, k)) * 0.35;
            c = (u > 0.995) ? pic : mix(emptyC(gFin, iq, u), hot, shown) + float3(1.0, 0.8, 0.45) * arc * 1.3 * (1.0 - gFin) + float3(1.0, 0.92, 0.65) * spark
              + float3(0.75, 0.4, 1.0) * corona;
        } else if (fx == 14) {                      // AA Eclipse Ink: purple-black ink blooms like a drop in water, then clears from the middle of each blot
            float2 w = iq + 24.0 * float2(fbm(iq / 70.0 + t * 0.25), fbm(iq / 70.0 - t * 0.2 + 9.0)) - 12.0;
            float cs = 130.0;
            float2 g = floor(w / cs); float2 f = fract(w / cs);
            float F1 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = length(sp - f);
                    if (dd < F1) { F1 = dd; best = g + sp; }
                }
            }
            float r = hash(floor(best * 3.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float inkR = ss(0.0, 0.45, ul) * 1.25;
            float clrR = ss(0.4, 1.0, ul) * 1.3;
            float ink = 1.0 - ss(inkR - 0.06, inkR, F1);
            float clr = 1.0 - ss(clrR - 0.05, clrR, F1);
            float edgeI = exp(-sq((F1 - inkR) / 0.05)) * ink;
            float rim = exp(-sq((F1 - clrR) / 0.04)) * step(0.01, clrR) * (1.0 - ss(0.85, 1.0, ul));
            float swirl = fbm(w / 35.0 + t * 0.4);
            float3 inkc = mix(float3(0.04, 0.015, 0.09), float3(0.42, 0.2, 0.85), sq(swirl) * 0.8 + edgeI * 0.7);
            float3 pic = im(iq, 0.0);
            c = (ul >= 1.0) ? pic : mix(mix(emptyC(gFin, iq, u), inkc, ink), pic * mix(float3(0.85, 0.75, 1.0), float3(1.0), ss(0.6, 1.0, ul)), clr)
              + float3(0.75, 0.5, 1.0) * rim * 0.8;
        } else if (fx == 15) {                      // AB Fireflies: warm lights swarm in, the picture glows in around them
            float cs = 46.0;
            float2 g = floor(q / cs);
            float glow = 0.0; float halo = 0.0;
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 o = g + float2(float(i), float(j));
                    float r = hash(o + 1.7);
                    if (r < 0.55) {
                        float2 pos = (o + 0.5 + 0.38 * float2(sin(t * (1.3 + r) + r * 40.0), cos(t * (1.1 + 0.7 * r) + r * 23.0))) * cs;
                        float uc = clamp((pos.x - fr) / tail, 0.0, 1.0);
                        float life = ss(0.0, 0.08, uc) * (1.0 - ss(0.55, 0.85, uc + 0.15 * r));
                        float fl = 0.65 + 0.35 * sin(t * (6.0 + 5.0 * r) + r * 60.0);
                        float dl = length(q - pos);
                        glow += exp(-sq(dl / 3.2)) * life * fl;
                        halo += exp(-dl / 16.0) * life * fl;
                    }
                }
            }
            float n = vnoise(iq / 55.0) * 0.6 + vnoise(iq / 19.0) * 0.4;
            float rev = (u > 0.995) ? 1.0 : ss(n - 0.12, n + 0.12, u * 1.35 - 0.2 + halo * 0.25);
            float3 pic = im(iq, 0.0);
            float warm = 1.0 - ss(0.55, 1.0, u);
            c = (u > 0.995) ? pic : mix(emptyC(gFin, iq, u), pic * mix(float3(1.0), float3(1.15, 1.0, 0.65), warm * 0.6), rev)
              + float3(1.0, 0.92, 0.45) * glow * 1.2 + float3(0.95, 0.85, 0.3) * halo * 0.22;
        } else if (fx == 16) {                      // AC Ember Burn: ash outline first, a glowing ember line passes, the picture cools to true colour
            float n = fbm(iq / 70.0);
            float ash = ss(0.0, 0.2, u * 1.25 - n * 0.25);
            float k = u * 1.45 - n * 0.45;
            float lit = ss(0.5, 0.54, k);
            float line = exp(-sq((k - 0.52) / 0.022));
            float cool = ss(0.56, 1.0, k);
            float3 pic = im(iq, 0.0);
            float speck = 0.75 + 0.5 * hash(floor(iq / 2.0));
            float3 ashc = mix(emptyC(gFin, iq, u), float3(0.42, 0.40, 0.38) * lum(im(iq, 1.0)) * speck, ash);
            float3 warm = mix(pic * float3(1.3, 0.72, 0.42) + float3(0.25, 0.07, 0.0), pic, cool);
            float2 eg = float2(q.x + 8.0 * sin(q.y / 30.0 + t), q.y + t * 150.0);
            float2 cid = floor(eg / 11.0); float2 f = fract(eg / 11.0) - 0.5;
            float ember = step(0.94, hash(cid)) * exp(-dot(f, f) / 0.02) * ss(0.0, 0.1, u) * (1.0 - ss(0.4, 0.8, u))
                        * (0.6 + 0.4 * sin(t * 12.0 + hash(cid) * 50.0));
            c = (u > 0.995) ? pic : mix(ashc, warm, lit) + float3(1.0, 0.5, 0.12) * line * 1.3 + float3(1.0, 0.6, 0.2) * ember;
        } else if (fx == 17) {                      // AD Starfall: meteors streak in, the picture lights up where each lands, with a shock ring
            float cs = 105.0;
            float2 g = floor(iq / cs);
            float have = 0.0; float ring = 0.0; float trail = 0.0; float head = 0.0;
            float2 dv = normalize(float2(0.55 * dir, -1.0));
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 o = g + float2(float(i), float(j));
                    float2 ctr = (o + 0.2 + 0.6 * float2(hash(o), hash(o + 3.0))) * cs;
                    float uc = clamp(dir * (ctr.x + off - fr) / tail, 0.0, 1.0);
                    float rc = hash(o + 11.0);
                    float ph = uc * 1.5 - rc * 0.5;
                    if (ph > 0.0 && ph < 0.22) {
                        float kk = 1.0 - ph / 0.22;
                        float2 hp = ctr - dv * kk * 260.0;
                        head = max(head, exp(-sq(length(iq - hp) / 3.5)));
                        float sdl = segd(iq, hp, hp - dv * 110.0);
                        float along = clamp(dot(iq - hp, -dv) / 110.0, 0.0, 1.0);
                        trail = max(trail, exp(-sq(sdl / 1.8)) * (1.0 - along));
                    }
                    float ud = ss(0.22, 1.0, ph);
                    float rad = ud * 185.0;
                    float dl = length(iq - ctr);
                    have = max(have, step(dl, rad) * step(0.001, ud));
                    ring = max(ring, exp(-sq((dl - rad) / 5.0)) * (1.0 - ss(0.5, 1.0, ud)) * step(0.001, ud));
                    head = max(head, exp(-sq(dl / 9.0)) * (1.0 - ss(0.0, 0.25, ud)) * step(0.001, ud));
                }
            }
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - have) * 0.7;
            c = (u > 0.995) ? im(iq, 0.0) : mix(emptyC(gFin, iq, u), im(iq, 0.0), have) + float3(0.8, 0.7, 1.0) * ring * 0.8
              + float3(1.0, 0.95, 0.9) * head * 1.3 + float3(0.75, 0.6, 1.0) * trail * 0.9 + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 18) {                      // AE Petal Storm: petals swirl in on the wind and lie down flat into the picture
            float cell = 22.0;
            float rowr = hash(float2(floor(q.y / cell), 7.0));
            float fly = 340.0 * (0.5 + rowr);
            float xt = q.x;
            for (int k = 0; k < 4; k++) {
                float ut = clamp(dir * (xt - fr) / tail, 0.0, 1.0);
                float m = 1.0 - ss(0.0, 0.8, ut);
                xt = q.x + dir * m * m * fly;
            }
            float ut = clamp(dir * (xt - fr) / tail, 0.0, 1.0);
            float m = 1.0 - ss(0.0, 0.8, ut);
            float2 tq = float2(xt - off, q.y + sin(xt / 60.0 + t * 2.5) * 45.0 * m * m);
            float2 cid = floor(tq / cell);
            float r = hash(cid);
            float ul = ss(0.25 * r, 0.25 * r + 0.75, ut);
            float2 f = fract(tq / cell) - 0.5;
            float ang = m * (r * 6.283 + t * 4.0);
            float2 fr2 = float2(cos(ang) * f.x - sin(ang) * f.y, sin(ang) * f.x + cos(ang) * f.y);
            float el = length(fr2 * float2(1.0, 2.2));
            float rad = mix(0.3, 1.25, ss(0.3, 1.0, ul));
            float pet = (ul > 0.97) ? 1.0 : 1.0 - ss(rad - 0.08, rad, el);
            float alive = ss(0.0, 0.06, ut);
            float3 pc = mix(float3(1.0, 0.74, 0.86), im((cid + 0.5) * cell, 1.0), 0.25 + 0.4 * ul) * (0.85 + 0.5 * fr2.y);
            float3 pic = im(float2(q.x - off, q.y), 0.0);
            c = (ul > 0.97 && ut > 0.99) ? pic : mix(emptyC(gFin, float2(q.x - off, q.y), u), mix(pc, im(tq, 0.0), ss(0.6, 1.0, ul)), pet * alive);
        } else if (fx == 19) {                      // AF Frost Bloom: needle frost creeps across the glass, then it clears
            // with a finish pass on, the first pass frosts the whole glass over; the finish pass clears it
            if (finMode > 0 && arriving > 0.5) {
                if (gFin < 0.5) c = (u > 0.995) ? frostStill(iq) : frostFx(iq, freeze(iq, u), -9.0, emptyC(gFin, iq, u));
                else c = frostFx(iq, 9.0, u * 2.0 - 0.3, emptyC(gFin, iq, u));
            } else {
                c = (u > 0.995) ? im(iq, 0.0) : frostFx(iq, freeze(iq, min(u * 1.4, 1.0)), u * 2.2 - 1.0, emptyC(gFin, iq, u));
            }
        } else if (fx == 20) {                      // AG Aurora: green and purple light curtains wave across, the picture shows where they passed
            float wv = fbm(float2(q.y / 220.0, t * 0.25 + q.x / 400.0));
            float rays = pow(vnoise(float2(q.x / 16.0 + wv * 6.0, t * 0.6)), 2.5) * 0.7 + pow(vnoise(float2(q.x / 5.0 + wv * 9.0, 3.0)), 4.0) * 0.5;
            float env = exp(-sq((u - 0.2 + (wv - 0.5) * 0.12) / 0.13));
            float top = 1.0 - ss(0.0, Hm, q.y);
            float3 cc = mix(float3(0.25, 1.0, 0.55), float3(0.72, 0.35, 1.0), ss(0.15, 0.75, top + (wv - 0.5) * 0.4));
            float rev = ss(0.15, 0.85, u + (wv - 0.5) * 0.2);
            float3 pic = im(iq, 1.5 * (1.0 - ss(0.3, 0.9, u)));
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - rev) * 0.7;
            c = (u > 0.995) ? im(iq, 0.0) : mix(emptyC(gFin, iq, u), mix(pic * mix(cc, float3(1.0), 0.5), im(iq, 0.0), ss(0.6, 1.0, u)), rev)
              + cc * rays * env * (0.55 + 0.6 * top) + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 21) {                      // AH Glitch Warp: the picture arrives in RGB-split shifted bands that snap into place
            float bh = 14.0 + 26.0 * hash(float2(floor(q.y / 64.0), 3.0));
            float bid = floor(q.y / bh) + 97.0 * floor(q.y / 64.0);
            float r = hash(float2(bid, 1.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float k = 1.0 - ul;
            float jit = hash(float2(bid, floor(t * 20.0))) - 0.5;
            float sh = sign(r - 0.5) * k * k * 240.0 + jit * k * 50.0;
            float2 gq = iq + float2(sh, 0.0);
            float split = k * 16.0;
            float3 g3 = float3(im(gq + float2(split, 0.0), 0.0).r, im(gq, 0.0).g, im(gq - float2(split, 0.0), 0.0).b);
            float scan = 1.0 - 0.4 * k * step(0.5, fract(q.y / 3.0));
            float drop = step(hash(float2(bid, floor(t * 25.0)) + 7.0), 0.18 * k);
            float on = ss(0.0, 0.05, ul);
            float3 tint = mix(float3(1.0), float3(0.72, 0.5, 1.0), k * 0.6);
            float3 gl = g3 * scan * tint * (1.0 - drop) + float3(0.75, 0.55, 1.0) * drop * 0.7;
            // with a finish pass on, the first pass leaves it glitching in place; the finish pass snaps each band clean
            if (finMode > 0 && arriving > 0.5) {
                if (gFin < 0.5) {
                    c = (ul >= 1.0) ? glitchStill(iq) : mix(emptyC(gFin, iq, u), mix(gl, glitchStill(iq), ss(0.6, 1.0, ul)), on);
                } else {
                    float snap = ss(0.5, 0.56, ul);
                    c = mix(glitchStill(iq), im(iq, 0.0), snap) + float3(0.85, 0.75, 1.0) * exp(-sq((ul - 0.53) / 0.04)) * 0.5;
                }
            } else {
                c = (ul >= 1.0) ? im(iq, 0.0) : mix(emptyC(gFin, iq, u), gl, on);
            }
        }
 else if (fx == 22) {                      // AI Honeycomb Bloom: hex walls light up purple, each cell blooms out from its middle
            float2 hs = float2(1.0, 1.7320508);
            float2 pp = iq / 46.0;
            float2 a = mod(pp, hs) - hs * 0.5;
            float2 b = mod(pp - hs * 0.5, hs) - hs * 0.5;
            float2 gv = (dot(a, a) < dot(b, b)) ? a : b;
            float2 hid = floor((pp - gv) * 2.0 + 0.5);
            float hd = max(abs(gv.x) * 0.5 + abs(gv.y) * 0.8660254, abs(gv.x));
            float r = hash(hid);
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float edge = 0.5 - hd;
            float wall = (1.0 - ss(0.0, 0.035, edge)) * ss(0.0, 0.15, u) * (1.0 - ss(0.85, 1.0, u));
            float fillR = ul * 0.56;
            float filled = (ul > 0.97) ? 1.0 : 1.0 - ss(fillR - 0.025, fillR, hd);
            float bloom = exp(-sq((hd - fillR) / 0.03)) * (1.0 - ul) * step(0.02, ul);
            float glowIn = exp(-edge / 0.06) * ss(0.0, 0.2, ul) * (1.0 - ul) * 0.35;
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - filled) * (1.0 - gFin) * 0.8;
            c = mix(emptyC(gFin, iq, ul), im(iq, 0.0), filled) + float3(0.62, 0.32, 1.0) * wall * 0.95
              + float3(0.85, 0.6, 1.0) * bloom * 0.9 + float3(0.55, 0.3, 1.0) * glowIn + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 23) {                      // AJ Lightning Veins: branching veins flash, the picture floods out from them
            float v = 1.0 - abs(2.0 * fbm(iq / 110.0 + 3.0) - 1.0);
            float v2 = 1.0 - abs(2.0 * fbm(iq / 45.0 + 11.0) - 1.0);
            float stg = vnoise(iq / 160.0);
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * stg, 0.3 * stg + 0.7, u);
            float dist = 1.0 - v;
            float flood = ul * 1.05;
            float shown = (ul > 0.97) ? 1.0 : 1.0 - ss(flood - 0.04, flood, dist);
            float fl = 0.7 + 0.3 * step(0.5, hash(float2(floor(t * 24.0), floor(stg * 9.0))));
            float vein = (ss(0.93, 0.985, v) + ss(0.95, 0.99, v2) * 0.5 * ss(0.1, 0.3, ul)) * ss(0.0, 0.1, ul) * (1.0 - ss(0.75, 1.0, ul)) * fl;
            float rim = exp(-sq((dist - flood) / 0.025)) * step(0.01, ul) * (1.0 - ul);
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - shown) * (1.0 - gFin) * 0.8;
            c = mix(emptyC(gFin, iq, ul), im(iq, 0.0), shown) + float3(0.85, 0.75, 1.0) * vein * 1.1
              + float3(0.6, 0.35, 1.0) * rim * 0.8 + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 24) {                      // AK Stained Glass: dark lead lines, each pane lights up a jewel colour, then turns clear
            float cs = 70.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs);
            float F1 = 8.0; float F2 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = dot(sp - f, sp - f);
                    if (dd < F1) { F2 = F1; F1 = dd; best = g + sp; } else if (dd < F2) { F2 = dd; }
                }
            }
            float e = sqrt(F2) - sqrt(F1);
            float r = hash(floor(best * 5.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float lead = 1.0 - ss(0.02, 0.05, e);
            float3 jewel = (r < 0.25) ? float3(0.55, 0.25, 1.0) : (r < 0.5) ? float3(0.25, 0.45, 1.0) : (r < 0.75) ? float3(0.95, 0.3, 0.75) : float3(1.0, 0.75, 0.3);
            float lit = ss(0.0, 0.3, ul);
            float clr = ss(0.55, 1.0, ul);
            float3 pane = im(iq, 1.8) * jewel * 1.6 * (0.8 + 0.4 * exp(-sqrt(F1) * 2.0));
            float3 body = mix(pane, im(iq, 0.0), clr);
            float leadShow = ss(0.0, 0.12, u) * (1.0 - ss(0.8, 1.0, u));
            float3 leadC = float3(0.08, 0.05, 0.14) + float3(0.6, 0.35, 1.0) * exp(-e / 0.015) * 0.6;
            c = (ul >= 1.0 && u > 0.995) ? im(iq, 0.0) : mix(mix(emptyC(gFin, iq, ul), body, lit), leadC, lead * leadShow);
        } else if (fx == 25) {                      // AL Tile Flip: tiles pop up purple side first, then flip over to the picture
            float cs = 54.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs) - 0.5;
            float r = hash(g + 4.0);
            float ul = (u > 0.995) ? 1.0 : ss(0.35 * r, 0.35 * r + 0.65, u);
            float ang = (1.0 - ss(0.25, 0.9, ul)) * 3.14159;
            float wf = max(abs(cos(ang)), 0.02);
            float pop = ss(0.0, 0.12, ul);
            float sz = mix(0.18, 0.5, ss(0.0, 0.25, ul));
            float inT = step(abs(f.x), sz * wf) * step(abs(f.y), sz);
            float lx = f.x / wf;
            float3 back = mix(float3(0.14, 0.07, 0.3), float3(0.55, 0.32, 1.0), 0.5 + f.y);
            back += float3(0.9, 0.85, 1.0) * (1.0 - ss(0.13, 0.16, length(float2(lx, f.y)))) * 0.6;
            float3 frontC = im((g + 0.5 + float2(lx, f.y)) * cs, 0.0);
            float shade = 0.55 + 0.45 * wf;
            float hl = exp(-sq((abs(f.x) - sz * wf) / 0.02)) * (1.0 - ss(0.9, 1.0, ul)) * pop;
            c = (ul >= 1.0) ? im(iq, 0.0) : mix(emptyC(gFin, iq, ul), ((ang < 1.5708) ? frontC : back) * shade, inT * pop)
              + float3(0.8, 0.65, 1.0) * hl * inT * 0.8;
        } else if (fx == 26) {                      // AM Spiral Galaxy: each cell swirls the picture in around a bright core
            float cs = 80.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs);
            float F1 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = dot(sp - f, sp - f);
                    if (dd < F1) { F1 = dd; best = g + sp; }
                }
            }
            float r = hash(floor(best * 5.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float2 v = iq - best * cs;
            float rr = length(v) / cs;
            float k = 1.0 - ss(0.0, 0.9, ul);
            float sw = k * k * 6.0 * exp(-rr * 1.5);
            float2 vr = float2(cos(sw) * v.x - sin(sw) * v.y, sin(sw) * v.x + cos(sw) * v.y);
            float a = atan(v.y, v.x);
            float R = ul * 1.15 * (1.0 + 0.18 * sin(a * 2.0 - rr * 14.0 + ul * 8.0) * k);
            float inside = (ul > 0.97) ? 1.0 : 1.0 - ss(R - 0.04, R, rr);
            float arm = exp(-sq((rr - R) / 0.03)) * k * step(0.02, ul);
            float core = exp(-rr / 0.07) * ss(0.0, 0.1, ul) * k;
            float3 pic = im(best * cs + vr, 1.2 * k);
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - inside) * (1.0 - gFin) * 0.8;
            c = mix(emptyC(gFin, iq, ul), mix(pic * mix(float3(0.7, 0.6, 1.0), float3(1.0), 1.0 - k), im(iq, 0.0), ss(0.85, 1.0, ul)), inside)
              + float3(0.55, 0.45, 1.0) * arm * 0.9 + float3(1.0, 0.92, 1.0) * core + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 27) {                      // AN Kintsugi: gold cracks light up, cells bloom from them, the gold lingers then fades
            float2 g = floor(iq / 64.0); float2 f = fract(iq / 64.0);
            float F1 = 8.0; float F2 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = dot(sp - f, sp - f);
                    if (dd < F1) { F2 = F1; F1 = dd; best = g + sp; } else if (dd < F2) { F2 = dd; }
                }
            }
            float e = sqrt(F2) - sqrt(F1);
            float r = hash(floor(best * 5.0));
            float ul = ss(0.25 * r, 0.25 * r + 0.75, u);
            float shim = 0.7 + 0.3 * sin(t * 4.0 + (iq.x + iq.y) / 40.0);
            float crack = (1.0 - ss(0.0, 0.045, e)) * ss(0.0, 0.15, u) * (1.0 - ss(0.92, 1.0, u)) * shim;
            float fillR = ul * 0.75;
            float filled = (ul > 0.97) ? 1.0 : 1.0 - ss(fillR - 0.03, fillR, e);
            float bloom = exp(-sq((e - fillR) / 0.035)) * (1.0 - ul) * step(0.02, ul);
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - filled) * (1.0 - gFin) * 0.8;
            c = mix(emptyC(gFin, iq, ul), im(iq, 0.0), filled) + float3(1.0, 0.75, 0.3) * crack
              + float3(1.0, 0.9, 0.6) * bloom * 0.8 + float3(0.9, 0.9, 1.0) * star;
        } else if (fx == 28) {                      // AO Flower Bloom: each cell opens like a five-petal flower until it covers the cell
            float cs = 76.0;
            float2 g = floor(iq / cs); float2 f = fract(iq / cs);
            float F1 = 8.0; float2 best = float2(0.0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float2 sp = seed(g + float2(float(i), float(j))) - g;
                    float dd = dot(sp - f, sp - f);
                    if (dd < F1) { F1 = dd; best = g + sp; }
                }
            }
            float r = hash(floor(best * 5.0));
            float ul = (u > 0.995) ? 1.0 : ss(0.3 * r, 0.3 * r + 0.7, u);
            float2 v = iq - best * cs;
            float rr = length(v) / cs;
            float a = atan(v.y, v.x);
            float lob = abs(cos(a * 2.5 + r * 6.283));
            float R = ul * 1.25 * mix(0.55 + 0.45 * lob, 1.0, ss(0.6, 1.0, ul));
            float inside = (ul > 0.97) ? 1.0 : 1.0 - ss(R - 0.03, R, rr);
            float rim = exp(-sq((rr - R) / 0.03)) * (1.0 - ul) * step(0.02, ul);
            float tint = (1.0 - ss(0.5, 1.0, ul)) * ss(R * 0.3, R, rr);
            float k7 = floor(hash(best + 9.0) * 7.0);
            float3 fc = (k7 < 1.0) ? float3(1.0, 0.22, 0.3) : (k7 < 2.0) ? float3(1.0, 0.85, 0.2) : (k7 < 3.0) ? float3(1.0, 0.55, 0.15)
                      : (k7 < 4.0) ? float3(1.0, 0.5, 0.78) : (k7 < 5.0) ? float3(0.72, 0.5, 1.0) : (k7 < 6.0) ? float3(1.0, 0.97, 0.92) : float3(0.4, 0.62, 1.0);
            float3 pollenC = (k7 < 1.0 || k7 > 4.5) ? float3(1.0, 0.85, 0.3) : float3(0.55, 0.3, 0.1);
            float3 pic = im(iq, 0.0);
            float3 pc = mix(pic, fc * (0.55 + 0.6 * lum(pic)) * (0.75 + 0.35 * ss(0.0, R, rr)), tint * 0.85);
            float pollen = exp(-sq(rr / 0.06)) * ss(0.0, 0.1, ul) * (1.0 - ss(0.5, 0.9, ul));
            float star = step(0.996, hash(floor(q / 3.0))) * (1.0 - inside) * (1.0 - gFin) * 0.8;
            c = mix(emptyC(gFin, iq, ul), pc, inside) + mix(fc, float3(1.0), 0.35) * rim * 0.9 + pollenC * pollen * 1.2
              + float3(0.9, 0.9, 1.0) * star;
        }

 else if (fx == 29) {                      // AP Frosted Glass: the picture is seen through smoked frosted
            // glass; the in-focus side spreads from the right edge to the left as it opens (smooth, no front), the smoke lifts
            // to a milky see-through frost, the far left clears last.
            float e; float bl; float dk; float mk;
            if (gFin > 0.5) { float f = sin(3.14159 * u) * depth; e = 1.0; bl = f; dk = 0.0; mk = f * 0.5; }
            else {
                // starts a full gradient past the right edge (all frosted), ends past the left edge
                // timing: focus creeps then speeds up, smoke holds to ~45%,
                // a short milky stage near the end, then the last blur snaps clear
                float xf = Wm * (1.65 - 2.45 * pow(pg, 1.6));
                e = clamp((xf - q.x) / (Wm * 0.6), 0.0, 1.0);
                bl = 1.0 - ss(0.92, 1.0, pg);
                dk = 0.85 * (1.0 - ss(0.45, 0.85, pg));
                mk = ss(0.7, 0.86, pg) * (1.0 - ss(0.93, 1.0, pg));
            }
            float be = bl * pow(e, 1.1);
            float lvl = 2.8 * be;
            float sm = 22.0 * be;
            float3 pic = im(q, lvl) * 0.3 + (im(q + float2(sm, 0.0), lvl) + im(q - float2(sm, 0.0), lvl)) * 0.22
                       + (im(q + float2(2.0 * sm, 0.0), lvl) + im(q - float2(2.0 * sm, 0.0), lvl)) * 0.13;
            float3 milky = mix(pic, float3(lum(pic)) * 0.6 + 0.42, mk * e * 0.75);
            float darken = dk * pow(e, 1.2);
            c = (pg >= 0.999 && gFin < 0.5) ? im(q, 0.0) : milky * (1.0 - darken);
        }
        else if (fx == 30 || fx == 31 || fx == 32) {   // AQ Snow Day, AR Autumn Leaves, AS Gift Wrap: one fill across both screens, then a reveal
            // how covered: the cover screen does the first part over its own home screen, the inner continues over its
            // home screen and is full for the last 20%; with no finish pass the inner starts full and the sweep reveals
            float w = (arriving < 0.5) ? COVER_PART * ss(0.0, 0.8, 1.0 - pg)
                    : (finMode > 0 && gFin < 0.5) ? COVER_PART + (1.0 - COVER_PART) * ss(0.0, 0.8, pg) : 1.0;
            float3 under = (arriving < 0.5) ? im(iq, 0.0) : im(q, 0.0);
            bool reveal = arriving > 0.5 && (gFin > 0.5 || finMode == 0);
            float u0 = (gFin > 0.5) ? d / tail : dir * d / tail;
            // ONE call per effect (the GPU compiler inlines every call site; three copies of leaves() was too big
            // for the Fold's GPU: 'Pipeline create failed' 11-08 17:47)
            if (fx == 30) {
                c = reveal ? plow(q, u, snowTex(q), under) : snowFill(q, w, under);
            } else if (fx == 31) {
                c = leaves(q, reveal ? u : w, reveal ? u0 : w, reveal ? dir : 0.0, reveal ? 0.0 : 1.0, reveal ? 9.0 : 0.0,
                           reveal ? 1.0 : 0.0, reveal ? 0.0 : -9.0, reveal ? float3(0.0) : under, reveal ? under : float3(0.0), Hm);
            } else {
                float3 wrapped = giftWrap(q, reveal ? 1.0 : w, under, Wm, Hm);
                c = reveal ? giftTear(q, u, wrapped, under) : wrapped;
            }
        }
    return c;
}

// the held picture between the fill and the finish pass (Sketch's black and white, Hologram, ...), or the plain/soft picture
float3 holdPic(float2 x) {
    if (fx == 3) return sketchGrey(x);
    if (fx == 4) return holoStill(x);
    if (fx == 1) return pixelStill(x);
    if (fx == 5) return crystalStill(x);
    if (fx == 21) return glitchStill(x);
    if (fx == 19) return frostStill(x);
    if (fx < 30 && (soft > 0.01 || wobble > 0.01)) return liquid(x);
    return im(x, 0.0);
}

// effect() and holdPic() are called ONCE each: the GPU compiler inlines every call site, and two copies of the whole
// effect() was more than the Fold's GPU would compile ('Pipeline create failed', 11-08 17:47)
half4 main(float2 p) {
    float2 q = p / S;
    float Wm = res.x / S;
    float Hm = res.y / S;
    bool arr = arriving > 0.5;
    bool finP = fin >= 0.0 && arr;
    float base; float fr; float d; float off; float u; float dirE; float pgv; float gFin; float2 iq;
    float sd = 0.0;
    if (!finP) {
        float dir = arr ? 1.0 : -1.0;
        // start and end with the whole band off-screen so prog 0 and 1 are exactly "nothing happening"
        base = (Wm + 2.0 * amp + tail) * (1.0 - prog) - amp - (arr ? tail : 0.0);
        fr = base + wave(q.y, Hm);
        d = q.x - fr;
        off = arr ? slide * max(base, 0.0) : -slide * (Wm - clamp(base, 0.0, Wm));
        iq = float2(q.x - off, q.y);
        u = clamp(dir * d / tail, 0.0, 1.0);
        dirE = dir; pgv = arr ? prog : 1.0 - prog; gFin = 0.0;
    } else {
        // the finish pass: the effect runs once more over the built picture, solid behind it
        float E = (fx == 7) ? tail * 0.75 : tail;
        float dirF = 1.0;
        if (finMode == 1) {
            float fx2 = -amp - E + (Wm + 2.0 * amp + 2.0 * E) * fin;
            sd = fx2 + wave(q.y, Hm) - q.x; dirF = -1.0; base = fx2;
        } else if (finMode == 2) {
            float fx2 = Wm + amp + E - (Wm + 2.0 * amp + 2.0 * E) * fin;
            sd = q.x - fx2 - wave(q.y, Hm); base = fx2;
        } else {
            float2 ctr = float2(Wm, Hm) * 0.5;
            float2 v = q - ctr;
            float R = (length(ctr) + amp + E) * fin;
            sd = R + amp * 0.7 * sin(atan(v.y, v.x) * 5.0 + t * 3.0) - length(v); base = -9999.0;
        }
        u = clamp(sd / E, 0.0, 1.0);
        fr = q.x - dirF * u * tail; d = u * tail; off = 0.0; iq = q;
        dirE = dirF; pgv = fin;
        // R Eclipse Bloom finishes differently: its glow is blended over the picture instead of a rebuild
        gFin = (fx == 7) ? 0.0 : 1.0;
    }
    float3 e = effect(q, iq, u, fr, dirE, off, d, pgv, gFin, Wm, Hm, base);
    bool special = fx == 3 || fx == 4 || fx == 21 || fx == 1 || fx == 5 || fx == 19 || soft > 0.01 || wobble > 0.01;
    float3 hold = (arr && (finP || (finMode > 0 && fx < 30 && special))) ? holdPic(iq) : float3(0.0);
    float3 c;
    if (!finP) {
        // the end of the fill blends into the held picture, so the finish pass starts from exactly it
        c = (arr && finMode > 0 && fx < 30 && special) ? mix(e, hold, ss(0.88, 1.0, u)) : e;
    } else {
        float3 pic = im(q, 0.0);
        float s = depth * ss(0.0, 0.1, u) * (1.0 - ss(0.45, 1.0, u));
        // 30-32 (one fill, then a reveal): the effect at u = 0 is the covered state, so it draws ahead of the front too
        c = (fx >= 30) ? e : (sd <= 0.0) ? hold : (gFin > 0.5) ? e : mix(mix(hold, pic, ss(0.0, 0.35, u)), e, s);
    }
    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""

/** The whole shader source (the parts joined at run time so the compiler does not fold them back into one constant). */
val FX_SHADER: String = arrayOf(FX_SHADER_A, FX_SHADER_B, FX_SHADER_C).joinToString("")

/**
 * The shader for one effect: fx becomes a constant, so the compiler drops every other effect's code. One shader with
 * all of them in it was more than the Fold's GPU would compile ("Pipeline create failed", 11-08).
 */
fun fxShaderSource(fx: Int): String = FX_SHADER.replace("uniform int fx;", "const int fx = $fx;")

