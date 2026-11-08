package com.vibecodedstudio.nightfold

/** Effects in shader order (index = the fx uniform). The letters are the names shown in the app and stay fixed. */
val EFFECTS = listOf(
    "K  Stardust", "L  Pixel Assemble", "M  Sandstorm", "N  Sketch to Paint",
    "O  Hologram", "P  Crystal", "Q  Raindrops", "R  Eclipse Bloom",
    "S  Corona", "T  Moon Shards", "U  Constellation", "V  Nebula Burn", "W  Blood Moon",
    "X  Solar Flare", "AA  Eclipse Ink", "AB  Fireflies", "AC  Ember Burn", "AD  Starfall", "AE  Petal Storm",
    "AF  Frost Bloom", "AG  Aurora", "AH  Glitch Warp", "AI  Honeycomb Bloom", "AJ  Lightning Veins", "AK  Stained Glass",
    "AL  Tile Flip", "AM  Spiral Galaxy", "AN  Kintsugi", "AO  Flower Bloom", "AP  Frosted Glass",
)

/** Effect list groups for the app's list (indices into EFFECTS). */
val FX_GROUPS = listOf(listOf(7, 8, 10, 11), (22..29).toList(), (14..21).toList(), (0..6).toList())

/** Not listed in the app (T Moon Shards, W Blood Moon, X Solar Flare), but kept in the shader so saved indices stay valid. */
val FX_HIDDEN = setOf(9, 12, 13)

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
    22f, 26f, 20f, 24f, 18f, 22f, 28f, 30f, 12f, 22f, 24f, 20f, 18f, 22f, 24f, 22f, 4f)
val TAIL = floatArrayOf(620f, 640f, 560f, 700f, 600f, 620f, 640f, 620f, 640f, 620f, 680f, 620f, 640f,
    620f, 660f, 660f, 660f, 640f, 640f, 640f, 680f, 600f, 640f, 660f, 640f, 640f, 660f, 620f, 660f, 1100f)

/**
 * One AGSL shader for every effect. The sweep: a wavy front travels from the outer edge toward the hinge
 * (right -> left). On the cover screen the picture leaves (arriving = 0); on the inner screen it arrives (arriving = 1).
 * With slide = 1 the picture itself moves with the front. d = x - front: d > 0 = already swept.
 * Images: img0 full size, img1 = 1/4, img2 = 1/16, img3 = 1/64 (box-filtered), used as cheap blur levels 0..3.
 */
const val FX_SHADER = """
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
// what an effect builds out of: black in the fill; in the finish pass the finished picture, dimmed (noise-broken, no line)
float3 emptyC(float gFin, float2 iq, float u) {
    if (gFin < 0.5) return float3(0.0);
    float3 a = ((soft > 0.01 || wobble > 0.01)) ? liquid(iq) : im(iq, 0.0);
    float n = vnoise(iq / 45.0) * 0.6 + vnoise(iq / 14.0) * 0.4;
    float x = u * 1.3 - n * 0.3;
    return a * mix(float3(1.0), float3(0.30, 0.20, 0.50), depth * ss(0.0, 0.2, x));
}
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
            float lv = floor(min(ul * 5.0, 4.0));
            float sz = 48.0 / exp2(lv);
            float2 f = fract(iq / sz);
            float2 cc = (floor(iq / sz) + 0.5) * sz;
            float lvl = clamp(log2(sz * S) * 0.5 - 0.6, 0.0, 3.0);
            float pop = ss(0.0, 0.08, ul);
            float g = (1.0 - pop) * 0.45;
            float inside = (f.x > g * 0.5 && f.x < 1.0 - g * 0.5 && f.y > g * 0.5 && f.y < 1.0 - g * 0.5) ? 1.0 : 0.0;
            float bevel = (1.0 - ul) * 0.35 * (step(f.x, 0.08) + step(f.y, 0.08)) - (1.0 - ul) * 0.25 * (step(0.92, f.x) + step(0.92, f.y));
            float3 blk = im(cc, lvl) * (1.0 + bevel);
            c = mix(emptyC(gFin, iq, u), mix(blk, im(iq, 0.0), ss(0.88, 1.0, ul)), inside * pop)
              + float3(0.5, 0.9, 1.0) * inside * pop * (1.0 - ss(0.0, 0.3, ul)) * 0.5;
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
            c = mix(mix(emptyC(gFin, iq, u), holo, fill), img, real) + float3(0.3, 0.95, 1.0) * outline
              + float3(0.5, 1.0, 1.0) * exp(-sq((ur - 0.15) / 0.05)) * 0.6;
        } else if (fx == 5) {                       // P Crystal: ice crystals grow in each cell, glint, then melt to the picture
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
            float grow = ss(0.0, 0.45, ul);
            float inC = step(sqrt(F1), grow * 1.1);
            float facet = 1.0 + 0.35 * dot(normalize(bo + 0.0001), float2(0.7, -0.7));
            float3 ice = mix(im(best * cs, 2.6), float3(0.75, 0.9, 1.0), 0.35) * facet;
            float edge = 1.0 - ss(0.0, 0.06, sqrt(F2) - sqrt(F1));
            float melt = ss(0.65, 1.0, ul);
            c = mix(emptyC(gFin, iq, u), mix(ice + float3(0.9, 0.97, 1.0) * edge * 0.8, im(iq, 0.0), melt), inC)
              + float3(0.8, 0.95, 1.0) * exp(-sq((sqrt(F1) - grow * 1.1) / 0.03)) * (1.0 - melt) * 0.7;
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
        } else if (fx == 19) {                      // AF Frost Bloom: ferns of frost branch across the glass, then melt clear
            float n = fbm(iq / 60.0);
            float grow = ss(0.0, 0.08, u * 1.4 - n * 0.5);
            float2 rq = float2(iq.x * 0.8 - iq.y * 0.6, iq.x * 0.6 + iq.y * 0.8);
            float r1 = 1.0 - abs(2.0 * vnoise(iq / 14.0 + float2(n * 3.0, 0.0)) - 1.0);
            float r2 = 1.0 - abs(2.0 * vnoise(rq / 7.0 + n * 2.0) - 1.0);
            float fern = pow(r1, 8.0) * 0.8 + pow(r2, 10.0) * 0.5;
            float mk = u + (n - 0.5) * 0.35;
            float melt = ss(0.45, 0.85, mk);
            float3 frosted = mix(im(iq, 2.6), float3(0.82, 0.9, 1.0), 0.45);
            float wet = exp(-sq((mk - 0.47) / 0.03));
            c = (u > 0.995) ? im(iq, 0.0) : mix(mix(emptyC(gFin, iq, u), frosted + float3(0.9, 0.97, 1.0) * fern, grow), im(iq, 0.0), melt)
              + float3(0.85, 0.95, 1.0) * wet * 0.35 * grow;
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
            c = (ul >= 1.0) ? im(iq, 0.0) : mix(emptyC(gFin, iq, u), g3 * scan * tint * (1.0 - drop) + float3(0.75, 0.55, 1.0) * drop * 0.7, on);
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
    return c;
}

half4 main(float2 p) {
    float2 q = p / S;
    float Wm = res.x / S;
    float Hm = res.y / S;
    bool arr = arriving > 0.5;
    float dir = arr ? 1.0 : -1.0;
    float3 c;
    if (fin < 0.0 || !arr) {
        // start and end with the whole band off-screen so prog 0 and 1 are exactly "nothing happening"
        float base = (Wm + 2.0 * amp + tail) * (1.0 - prog) - amp - (arr ? tail : 0.0);
        float fr = base + wave(q.y, Hm);
        float d = q.x - fr;
        float off = arr ? slide * max(base, 0.0) : -slide * (Wm - clamp(base, 0.0, Wm));
        float2 iq = float2(q.x - off, q.y);
        float u = clamp(dir * d / tail, 0.0, 1.0);
        c = effect(q, iq, u, fr, dir, off, d, arr ? prog : 1.0 - prog, 0.0, Wm, Hm, base);
        if (arr && finMode > 0 && fx == 3) c = mix(c, sketchGrey(iq), ss(0.88, 1.0, u));
        else if (arr && finMode > 0 && (soft > 0.01 || wobble > 0.01)) c = mix(c, liquid(iq), ss(0.88, 1.0, u));
    } else {
        // the finish pass: the effect runs once more over the built picture, solid behind it
        float E = (fx == 7) ? tail * 0.75 : tail;
        float sd; float dirF = 1.0; float base;
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
        float ue = clamp(sd / E, 0.0, 1.0);
        float3 pic = im(q, 0.0);
        float3 ahead = (fx == 3) ? sketchGrey(q) : ((soft > 0.01 || wobble > 0.01)) ? liquid(q) : pic;
        // R Eclipse Bloom finishes differently: its glow is blended over the picture instead of a rebuild
        float gF = (fx == 7) ? 0.0 : 1.0;
        float3 e = effect(q, q, ue, q.x - dirF * ue * tail, dirF, 0.0, ue * tail, fin, gF, Wm, Hm, base);
        float s = depth * ss(0.0, 0.1, ue) * (1.0 - ss(0.45, 1.0, ue));
        c = (sd <= 0.0) ? ahead : (gF > 0.5) ? e : mix(mix(ahead, pic, ss(0.0, 0.35, ue)), e, s);
    }
    return half4(half3(clamp(c, 0.0, 1.0)), 1.0);
}
"""
