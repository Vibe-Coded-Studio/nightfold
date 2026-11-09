package com.vibecodedstudio.nightfold

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** A screen's picture with its cheap blur levels (1, 1/4, 1/16, 1/64 by repeated box-filtered halving). */
class Pic(val levels: List<Bitmap>) {
    val w get() = levels[0].width.toFloat()
    val h get() = levels[0].height.toFloat()

    companion object {
        fun load(ctx: Context, name: String, fallbackW: Int, fallbackH: Int, hue: Int): Pic =
            load(File(ctx.getExternalFilesDir(null), name), fallbackW, fallbackH, hue)

        fun load(f: File, fallbackW: Int, fallbackH: Int, hue: Int): Pic {
            val src = (if (f.exists()) BitmapFactory.decodeFile(f.path) else null) ?: placeholder(fallbackW, fallbackH, hue)
            val lv = mutableListOf(src)
            var cur = src
            for (step in 1..6) {
                cur = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
                if (step % 2 == 0) lv.add(cur)
            }
            return Pic(lv)
        }

        private fun placeholder(w: Int, h: Int, hue: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.HSVToColor(floatArrayOf(hue.toFloat(), .7f, .8f)),
                Color.HSVToColor(floatArrayOf((hue + 80f) % 360, .8f, .3f)), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null; p.color = Color.WHITE
            for (i in 0 until 24) c.drawCircle((i % 4 + 0.5f) * w / 4, (i / 4 + 0.5f) * h / 6, w / 16f, p)
            return b
        }
    }
}

/**
 * The effect itself, independent of where it is drawn: the app's preview + fallback overlay (FxView) and the
 * shell-user helper's own SurfaceControl layer (HelperService) use the same frames.
 */
class FxRenderer(private var cover: Pic, private var inner: Pic) {
    /** New home-screen captures; rebinds on the next frame. */
    fun setPics(c: Pic, i: Pic) { cover = c; inner = i; bound = null }
    var fx = 0
    var slide = true
    /** The open plays on a timer, not on each ~11 deg hinge step: the cover drains in [coverMs] from the first
     *  hinge reading, the inner fills in [innerMs] from when the inner panel lights. The defaults are slower than a
     *  real open (measured on a Galaxy Z Fold: first move to panel swap ~229 ms, swap to flat ~130 ms) so the effect
     *  is easy to see; users tune or train from there. Closing stays angle-driven. */
    var coverMs = DEFAULT_COVER_MS
    var innerMs = DEFAULT_INNER_MS
    /** Finish pass: when the fill reaches 100% the effect runs across once more and leaves the solid screen behind
     *  it. 0 off, 1 back the other way, 2 same way again, 3 out from the middle. */
    var finMode = 0
    var finMs = DEFAULT_FIN_MS
    /** Blur of the built picture until the finish pass sharpens it (0 = none). */
    var softBlur = DEFAULT_SOFT_BLUR
    var softWobble = 0f
    private var finHead = -1f              // -1 = not in the finish pass
    private var opening = false
    private var finished = false        // this open's timed fill is done: stay off until a close or a new open
    private var usedTs = 0L
    private var coverHead = 0f
    private var innerHead = -1f            // -1 = inner not shown yet in this open
    private var lastNs = 0L
    private var pendTs = 0L; private var pendAngle = 0f; private var pendNow = 0L    // a small first rise, waiting

    /** Follows the raw readings: starts an open at the first rise from closed, ends it on a close or when done. */
    private fun clock(innerShape: Boolean, innerLit: Boolean) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = nowNs
        val sn = HingeReader.snap(); val ts = sn.ts; val la = sn.a; val pa = sn.pa
        // a small first rise waits for a 2nd, higher reading: a lone stray reading of a closed phone (0 -> 2 deg as
        // it came out of a pocket, 11-08 19:38, next reading 3.8 s later) started an open nothing could end before
        // the 3 s safety. A real open sends a reading every ~40 ms. A big first jump (opened with the cover off:
        // the first reading can be 28-179 deg) is an open on its own.
        if (pendTs != 0L && nowNs - pendNow > PEND_NS) { log?.invoke("stray hinge reading ignored (${pendAngle} deg, no 2nd)"); pendTs = 0L }
        if (ts != usedTs && pa >= 0f) {
            usedTs = ts
            val second = pendTs != 0L && la > pendAngle
            if (!opening && !second && pa <= 20f && la > pa && la < START_ALONE) {
                pendTs = ts; pendAngle = la; pendNow = nowNs
            } else if (!opening && (second || (pa <= 20f && la > pa))) {   // 20 not 12: a hinge wiggle (30 -> 14 deg) must not eat the real open
                val firstTs = if (second) pendTs else ts
                pendTs = 0L
                opening = true; finished = false
                // the first moving reading is ~40 ms after the move began, and it reaches us late too
                coverHead = ((nowNs - (firstTs - 40_000_000L)) / 1e6f / coverMs).coerceIn(0f, 0.5f)
                innerHead = -1f; finHead = -1f
                log?.invoke("open start: reading $la deg, cover head ${"%.2f".format(coverHead)}, fx ${EFFECTS[fx]}, finish $finMode")
            } else if (!opening && pendTs != 0L) {
                pendTs = 0L     // the 2nd reading was not higher: not an open
            } else if (opening && innerHead < 0f && !innerShape && la <= 3f) {
                // a closed phone's hinge jitters a few degrees when it is picked up or put down (0 -> 7 -> 0): that
                // starts an "open" the -8 deg close rule can't end, so the cover sat on the drained effect until the
                // 3 s safety. A real open never reads closed again before the inner panel takes over.
                opening = false; log?.invoke("open cancelled: hinge back to closed ($la deg)")
            } else if (opening && la < pa - 8f) {
                opening = false; log?.invoke("open -> close at $la deg")
            } else if (finished && la < pa - 8f) {
                finished = false; log?.invoke("close after done at $la deg")
            }
        }
        if (!opening) return
        // stopped half-way: hold the effect where it is until the hinge moves again
        val quiet = (nowNs - ts) / 1e9f
        val stopped = quiet > 0.15f && la in 15f..160f && !innerShape
        // the inner fill starts when the inner panel lights, not at the swap: the panel stays dark ~275-310 ms
        // after the swap (the phone is usually flat by then), so a fill started at the swap would be mostly unseen
        if (innerShape && innerLit && innerHead < 0f) { innerHead = 0f; log?.invoke("inner lit: fill starts") }
        if (!stopped) {
            coverHead += dt * 1000f / coverMs
            if (innerHead >= 0f) innerHead += dt * 1000f / innerMs
            if (finMode > 0 && innerHead >= 1f) {
                if (finHead < 0f) { finHead = 0f; log?.invoke("fill done: finish pass") } else finHead += dt * 1000f / finMs
            }
        }
        if (endHead() >= 1f + FADE_MS / endMs()) { opening = false; finished = true; log?.invoke("open done") }
        // the stopped-half-way hold must not hold forever once the inner panel is waiting to light
        if (innerShape && !innerLit && quiet > 3f) opening = false
        if (quiet > 3f) opening = false        // safety
    }
    /** the timed phase that ends this open: the fill, or the finish pass when one is on */
    private fun endHead() = if (finMode > 0) (if (innerHead >= 1f) max(0f, finHead) else -1f) else innerHead
    private fun endMs() = if (finMode > 0) finMs.toFloat() else innerMs.toFloat()
    var active = false; private set
    // one small shader per effect (fxShaderSource), made the first time that effect draws and kept
    private val shaders = HashMap<Int, RuntimeShader>()
    private var shader = shaderFor(0)
    private fun shaderFor(n: Int) = shaders.getOrPut(n) { RuntimeShader(fxShaderSource(n)) }
    private val paint = Paint()
    private var bound: Pic? = null
    private var t0 = SystemClock.uptimeMillis()
    private var innerStart = -1f    // angle when the inner screen first showed this open (adaptive start)
    private var wasInner = false
    private var lastBusy = 0L
    var log: ((String) -> Unit)? = null
    private var loggedInner = false
    private var lastFrameLog = 0L

    private fun bind(p: Pic) {
        if (bound === p) return
        bound = p
        for ((i, b) in p.levels.withIndex()) {
            val s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            s.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("img$i", s)
        }
        shader.setFloatUniform("isz", p.w, p.h)
    }

    /**
     * Draws one frame (or nothing) for hinge angle [a] on a [dw]x[dh] screen. [opaqueBack] = clear the whole canvas
     * to black while fully on (overlay/helper), transparent otherwise. Returns true while the effect is showing.
     */
    fun draw(canvas: Canvas, dw: Int, dh: Int, innerShape: Boolean, a: Float, armedNow: Boolean, preview: Boolean,
             innerLit: Boolean = innerShape, previewFin: Float = -1f, previewProg: Float = -1f, testAlpha: Int = -1,
             panelOff: Boolean = false): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!preview) clock(innerShape, innerLit)
        val timed = opening && !preview
        if (!innerShape) finished = false
        // once the timed fill ends, do not fall back to the angle-driven fill: it would jump back to ~55% and
        // play again while the hinge is still short of flat
        if (finished && innerShape && !preview) { if (active) { active = false; t0 = now; log?.invoke("draw: done, effect off") }; return false }
        if (innerShape && !wasInner) innerStart = a.coerceIn(20f, 150f)
        if (!innerShape) innerStart = -1f
        wasInner = innerShape

        val start = if (preview) 90f else if (innerStart < 0) 30f else innerStart
        // cover: even pace from the first reading over the whole time the cover is on (it stays on to ~120 deg)
        val prog = if (preview && previewProg >= 0f) previewProg.coerceIn(0f, 1f)
            else if (timed) (if (innerShape) max(0f, innerHead).coerceIn(0f, 1f) else coverHead.coerceIn(0f, 1f))
            else if (innerShape) ss(start, 172f, a) else ((a - 4f) / (115f - 4f)).coerceIn(0f, 1f)
        // cover: on only once it is really opening; inner: on until flat (a wake-up reading alone does nothing)
        // a < 0 = no hinge reading yet (helper just started): never draw on a guess, or an already open inner
        // screen would be covered by the effect's first frame
        val known = preview || a >= 0f
        // parked: the hinge has sat still part-way (phone propped half open) with no timed open running. The
        // angle-driven fill would hold its frame forever: 11-09 06:00 it drew on the cover for 5 min at ~20 deg.
        // It comes back on the next hinge move.
        val parked = !preview && !timed && !armedNow &&
            (SystemClock.elapsedRealtimeNanos() - HingeReader.lastTsNs) / 1e9f > PARK_S
        // the panel we would draw on is off and nothing is arming the hand-off: nobody can see it
        val dark = !preview && panelOff && !armedNow
        val busy = preview || (known && !parked && !dark && (armedNow || timed || (if (innerShape) a < 176f else a > 6f)))
        if (busy) lastBusy = now
        val show = busy || (!parked && !dark && now - lastBusy < 300)
        if (!preview && active && !show && (parked || dark)) log?.invoke("effect stopped: ${if (dark) "screen off" else "hinge parked at ${a.toInt()} deg"}")
        if (!preview && (show != active || innerShape != loggedInner)) {
            loggedInner = innerShape
            log?.invoke("draw: show=$show inner=$innerShape ${dw}x$dh angle=${a.toInt()} prog=${"%.2f".format(prog)} armed=$armedNow")
        }
        if (show != active) {
            active = show
            // a motion that begins on the inner screen (cover was off, or closing) gets the full range
            if (show && innerShape) innerStart = 20f
            if (!show) t0 = now
        }
        if (!show) return false

        val sh = shaderFor(fx)
        if (sh !== shader) { shader = sh; bound = null }      // a new shader needs its pictures bound
        bind(if (innerShape) inner else cover)
        shader.setFloatUniform("res", dw.toFloat(), dh.toFloat())
        shader.setFloatUniform("S", dh / 936f)
        shader.setFloatUniform("prog", prog)
        shader.setFloatUniform("t", (now - t0) / 1000f)
        shader.setFloatUniform("arriving", if (innerShape) 1f else 0f)
        shader.setFloatUniform("slide", if (slide) 1f else 0f)
        shader.setFloatUniform("amp", AMP[fx])
        shader.setFloatUniform("tail", TAIL[fx])
        val fin = if (!innerShape || finMode == 0) -1f else if (timed) (if (innerHead >= 1f && finHead >= 0f) finHead.coerceIn(0f, 1f) else -1f)
            else if (preview) previewFin else -1f
        shader.setFloatUniform("fin", fin)
        shader.setIntUniform("finMode", finMode)
        shader.setFloatUniform("soft", softBlur)
        shader.setFloatUniform("wobble", softWobble)
        shader.setFloatUniform("depth", 0.85f)
        paint.shader = shader
        // fade the whole effect out at the very end so the real screen takes over cleanly
        paint.alpha = if (timed) (if (innerShape) (255 * (1f - ss(1f, 1f + FADE_MS / endMs(), max(0f, endHead())))).toInt()
                else (255 * ss(0f, 0.04f, coverHead)).toInt())
            else if (preview || armedNow) 255 else if (innerShape) (255 * (1f - ss(176f, 179.5f, a))).toInt()
            else (255 * ss(4f, 8f, a)).toInt()
        if (testAlpha >= 0) paint.alpha = testAlpha       // GpuTest: the fade's see-through draw
        // whole surface black while fully on (ready for the other panel's size), see-through while fading in/out
        if (!preview || testAlpha >= 0) canvas.drawColor(if (paint.alpha >= 255) Color.BLACK else Color.TRANSPARENT, android.graphics.PorterDuff.Mode.SRC)
        canvas.drawRect(0f, 0f, dw.toFloat(), dh.toFloat(), paint)
        if (!preview && now - lastFrameLog > 200) {
            lastFrameLog = now
            log?.invoke("frame inner=$innerShape angle=${a.toInt()} prog=${"%.2f".format(prog)} alpha=${paint.alpha}")
        }
        return true
    }
}

const val DEFAULT_COVER_MS = 500
const val DEFAULT_INNER_MS = 1000
const val DEFAULT_FIN_MS = 900
const val DEFAULT_SOFT_BLUR = 1.2f
private const val FADE_MS = 60f
private const val PEND_NS = 300_000_000L     // how long a small first rise waits for the 2nd reading
private const val PARK_S = 3f                 // hinge still this long, no timed open: the effect stops (like the 3 s safety)
private const val START_ALONE = 15f          // a first reading this high starts an open by itself (jitter seen: <= 11 deg)

internal fun ss(a: Float, b: Float, x: Float): Float {
    val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k)
}

/**
 * The app's preview of the effect: follows a slider drag or a scripted play. (The live effect is drawn only by the
 * Shizuku helper; the old app-overlay fallback was removed 11-09.)
 * Which screen it shows is previewInner: the inner screen is nearly square, the cover tall and thin.
 */
class FxView(ctx: Context) : View(ctx) {
    var fx: Int get() = r.fx; set(v) { r.fx = v }
    var slide: Boolean get() = r.slide; set(v) { r.slide = v }
    fun timing(coverMs: Int, innerMs: Int) { r.coverMs = coverMs; r.innerMs = innerMs }
    fun reloadPics() { r.setPics(Pic.load(context, "cover.png", 624, 986, 210), Pic.load(context, "inner.png", 1224, 924, 20)) }
    fun finish(mode: Int, ms: Int, blur: Float, wobble: Float) { r.finMode = mode; r.finMs = ms; r.softBlur = blur; r.softWobble = wobble }
    private var previewFin = -1f
    private var previewProg = -1f
    var manualAngle = 0f
    var previewInner = true
    var playStart = -1L             // playing a scripted open + close since this time
    var slowMo = false
    var onPreviewAngle: ((Float) -> Unit)? = null

    /** Play in the preview uses the same opening clock as the phone: the cover
     *  drains over coverMs, then the inner fills over innerMs, then the finish pass over finMs, a short hold, then a
     *  0.8 s close (closing is angle-driven on the phone too). Slow x4 stretches all of it. */
    private fun scripted(now: Long): Float? {
        if (playStart < 0) { previewProg = -1f; return null }
        val s = (now - playStart) / 1000f / (if (slowMo) 4f else 1f)
        fun ease(x: Float) = ss(0f, 1f, x)
        val cov = r.coverMs / 1000f; val inn = r.innerMs / 1000f
        val fin = if (r.finMode > 0) r.finMs / 1000f else 0f
        val t1 = 0.3f + cov; val t2 = t1 + inn; val t3 = t2 + fin; val c0 = t3 + 0.4f
        previewProg = when {
            s < 0.3f -> 0f
            s < t1 -> (s - 0.3f) / cov
            s < t2 -> (s - t1) / inn
            s < t3 -> 1f
            else -> -1f
        }
        previewFin = if (r.finMode > 0 && s >= t2 && s < t3) ((s - t2) / fin).coerceIn(0f, 1f) else -1f
        previewInner = if (s < c0) s >= t1 else s < c0 + 0.8f
        val a = when {
            s < 0.3f -> 0f
            s < t1 -> 120f * ease((s - 0.3f) / cov)
            s < t2 -> 120f + 60f * ease((s - t1) / inn)
            s < c0 -> 180f
            s < c0 + 0.8f -> 180f * (1f - ease((s - c0) / 0.8f))
            s < c0 + 1.3f -> 0f
            else -> { playStart = -1L; previewProg = -1f; 0f }
        }
        return a
    }
    private val r = FxRenderer(Pic.load(ctx, "cover.png", 624, 986, 210), Pic.load(ctx, "inner.png", 1224, 924, 20))

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val sa = scripted(SystemClock.uptimeMillis())
        if (sa != null) { manualAngle = sa; onPreviewAngle?.invoke(sa) }
        if (playStart < 0) previewFin = -1f
        r.draw(canvas, width, height, previewInner, manualAngle, false, preview = true,
            previewFin = previewFin, previewProg = previewProg)
    }

    // redraw every frame while the preview is on screen (the effects animate even when held still)
    private val frameCb = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(t: Long) { invalidate(); android.view.Choreographer.getInstance().postFrameCallback(this) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.view.Choreographer.getInstance().postFrameCallback(frameCb)
    }
    override fun onDetachedFromWindow() {
        android.view.Choreographer.getInstance().removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }
}
