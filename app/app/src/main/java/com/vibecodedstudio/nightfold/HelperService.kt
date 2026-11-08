package com.vibecodedstudio.nightfold

import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceControl
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs as the shell user through Shizuku and draws the effect itself. Android's own panel-switch layer sits above
 * every app overlay (measured on a Galaxy Z Fold: with an app overlay, opening from the cover home screen still
 * flashed the real home screen for ~300 ms), so the effect lives on a shell-owned SurfaceControl at the very top of
 * the main display's layer stack:
 *  - no input window: touches always pass through (no 80% opacity cap, no solid/touchable switching)
 *  - the hinge is read here directly (the shell user can read logcat without a consent prompt)
 *  - pictures are read from the app's files dir on /sdcard
 * Under it, a black colour layer covers the panel switch until the first inner frame is on screen.
 * Fail-open: if this process dies SurfaceFlinger removes both layers, and the app falls back to its own overlay.
 */
class HelperService : IHelper.Stub {
    constructor() : super() { start() }

    private val thread = HandlerThread("fd-render").apply { start() }
    private val h = Handler(thread.looper)
    @Volatile private var enabled = false
    @Volatile private var st = "starting"
    @Volatile private var renderOk = false
    @Volatile private var fx = 0
    @Volatile private var slide = true
    @Volatile private var coverMs = DEFAULT_COVER_MS
    @Volatile private var innerMs = DEFAULT_INNER_MS
    @Volatile private var finMode = 0
    @Volatile private var finMs = DEFAULT_FIN_MS
    @Volatile private var softBlur = DEFAULT_SOFT_BLUR
    @Volatile private var softWobble = 0f

    // black handoff layer (under the render layer)
    private var black: SurfaceControl? = null
    private var blackStack = -1
    private var blackAlpha = -1f
    private var loggedAlpha = -1f
    // render layer
    private var fxLayer: SurfaceControl? = null
    private var fxStack = -1
    private var fxShown = false
    private var fxW = 0; private var fxH = 0
    private var renderer: HardwareRenderer? = null
    private val node = RenderNode("FoldDuoFx")
    private var fxr: FxRenderer? = null
    private val smoother = HingeSmoother()

    // ---- training: learn this user's opening speed from a few opens with the cover screen on ----
    @Volatile private var trainWant = 0
    private val trainCover = mutableListOf<Float>()
    @Volatile private var trainResult = "idle"
    private var tUsedTs = 0L; private var tStartNs = 0L; private var tSwapNs = 0L; private var tCoverOn = false

    private fun trainStep(innerNow: Boolean, swappedNow: Boolean) {
        if (trainWant <= 0) return
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (swappedNow && tStartNs > 0 && tSwapNs == 0L) tSwapNs = nowNs
        val ts = HingeReader.lastTsNs; val la = HingeReader.lastAngle; val pa = HingeReader.prevAngle
        if (ts == tUsedTs || pa < 0f) return
        tUsedTs = ts
        if (pa <= 12f && la > pa) {                       // an open starts (same rule as the effect clock)
            tStartNs = ts - 40_000_000L; tSwapNs = 0L
            tCoverOn = !innerNow && curState == 2           // only cover-ON opens have a cover stage to time
            return
        }
        if (tStartNs == 0L) return
        if (la < pa - 8f || ts - tStartNs > 1_500_000_000L) { tStartNs = 0L; return }   // closed again / too slow
        if (la >= 165f) {
            if (tCoverOn && tSwapNs > tStartNs) {
                trainCover.add((tSwapNs - tStartNs) / 1e6f)
                Log.d(TAG, "training open ${trainCover.size}/$trainWant: cover ${trainCover.last().toInt()} ms (first move -> panel swap)")
            } else Log.d(TAG, "training: open skipped (cover screen was off)")
            tStartNs = 0L
            if (trainCover.size >= trainWant) {
                fun med(x: List<Float>) = x.sorted()[x.size / 2].toInt()
                // inner speed is not trained: the inner panel lights ~300 ms after the swap, usually once the phone
                // is already flat, so the hinge says nothing about it; it stays a slider setting
                coverMs = med(trainCover)
                trainResult = "done $coverMs"; trainWant = 0
                Log.d(TAG, "training done: cover $coverMs ms")
            } else trainResult = "${trainCover.size}/$trainWant"
        }
    }

    private var lastInner = false
    private var lastState = -1
    private var onSince = 0L
    private var swapAt = 0L                 // when the inner panel appeared (shape change or ON)
    private var drawnAt = 0L                // our first inner frame went out
    private var offAt = 0L                  // last time the panel was not ON (arms the effect for a moment after)

    private lateinit var dmg: Any
    private lateinit var getInfo: Method
    private lateinit var setStack: Method
    private lateinit var setColor: Method
    private lateinit var setCrop: Method
    private lateinit var colorLayer: Method
    private lateinit var skipShot: Method

    private fun start() {
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            dmg = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
            getInfo = dmg.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            val tx = SurfaceControl.Transaction::class.java
            setStack = tx.getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
            setColor = tx.getMethod("setColor", SurfaceControl::class.java, FloatArray::class.java)
            setCrop = tx.getMethod("setWindowCrop", SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            skipShot = tx.getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
            colorLayer = SurfaceControl.Builder::class.java.getMethod("setColorLayer")
            st = "ready"
        } catch (e: Throwable) {
            st = "init failed: $e"; Log.e(TAG, st, e)
        }
        h.post {
            try { setupRenderer() } catch (e: Throwable) { renderOk = false; st = "render setup failed: $e"; Log.e(TAG, st, e) }
            HingeReader.onReading = { h.post { wake() } }
            HingeReader.start()
            h.post(tick)
        }
        cap.postDelayed(capTick, CAP_EVERY_MS)
    }

    /** buffer side: bigger than every built-in panel (inner + cover), so nothing resizes at the panel swap.
     *  From dumpsys display's DisplayDeviceInfo list (both panels, lit or not); 2448 (Z Fold) if it can't be read. */
    private val size: Int by lazy {
        val dims = Regex("""DisplayDeviceInfo\{"Built-in[^,]*, (\d+) x (\d+)""")
            .findAll(sh("dumpsys display | grep 'DisplayDeviceInfo{'")).flatMap { it.groupValues.drop(1).map(String::toInt) }.toList()
        (dims.maxOrNull() ?: 0).let { if (it in 1000..8000) it else 2448 }.also { Log.d(TAG, "buffer $it px (panels $dims)") }
    }

    private fun setupRenderer() {
        val dir = File("/sdcard/Android/data/com.vibecodedstudio.nightfold/files")
        fxr = FxRenderer(Pic.load(File(dir, "cover.png"), 624, 986, 210), Pic.load(File(dir, "inner.png"), 1224, 924, 20))
            .apply { log = { Log.d(TAG, it) } }
                val sc = SurfaceControl.Builder().setName("FoldDuo effect").setBufferSize(size, size)
            .setFormat(PixelFormat.RGBA_8888).setHidden(true).build()
        val t = SurfaceControl.Transaction()
        skipShot.invoke(t, sc, true)
        t.setLayer(sc, Int.MAX_VALUE - 4)
        t.apply()
        fxLayer = sc
        renderer = HardwareRenderer().apply {
            setSurface(Surface(sc))
            setContentRoot(node)
            isOpaque = false
        }
        node.setPosition(0, 0, size, size)
        renderOk = true
        Log.d(TAG, "renderer ready")
    }

    private fun intField(o: Any, n: String) = o.javaClass.getField(n).getInt(o)

    // Display state poll + black layer at ~120 Hz (measured on a Galaxy Z Fold: the panel takes ~140 ms to light).
    // Drawing runs on vsync below, with this poll as a watchdog so a missed vsync while the panel is off never freezes it.
    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (e: Throwable) { st = "tick: $e"; Log.e(TAG, st, e); hideAll() }
            if (shouldIdle()) goIdle() else h.postDelayed(this, 8)
        }
    }

    // ---- idle (battery): the 8 ms display poll only runs while the hinge moves. A panel swap can't happen
    // without the hinge moving, and the first reading of an open (~4-20 deg) comes long before the swap (~100 deg).
    private var idle = false                // touched on the render thread only
    private fun shouldIdle() = !enabled || (!fxShown && smoother.quietFor() > QUIET_S)
    private fun goIdle() {
        idle = true
        // never leave the black layer up: the panel could be woken later without the hinge moving
        if (blackAlpha > 0f) applyBlack(0f, curStack)
        loggedAlpha = -1f
        st = "idle (hinge still)"
        Log.d(TAG, "idle: hinge still, polling stopped")
    }
    private fun wake() {
        if (!idle) return
        idle = false
        baseline()
        Log.d(TAG, "wake: hinge moved")
        tick.run()
    }
    /** what the display is doing NOW, without treating changes made while idle (screen slept / woke) as a swap */
    private fun baseline() {
        if (!::getInfo.isInitialized) return
        val info = getInfo.invoke(dmg, 0) ?: return
        val w = intField(info, "logicalWidth"); val hgt = intField(info, "logicalHeight")
        val state = intField(info, "state")
        val now = SystemClock.uptimeMillis()
        lastInner = minOf(w, hgt).toFloat() / maxOf(w, hgt) > 0.7f
        if (state == 2 && lastState != 2) onSince = now
        if (state != 2) offAt = now
        lastState = state
    }
    private var lastFrameAt = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            try { render() } catch (e: Throwable) { st = "render: $e"; Log.e(TAG, st, e); renderOk = false; hideFx() }
            if (fxShown) Choreographer.getInstance().postFrameCallback(this) else vsyncOn = false
        }
    }
    private var vsyncOn = false

    private var curW = 0; private var curH = 0; private var curInner = false; private var curState = -1; private var curStack = -1

    private fun step() {
        if (!enabled || !::getInfo.isInitialized) { hideAll(); return }
        val info = getInfo.invoke(dmg, 0) ?: run { hideAll(); return }
        val w = intField(info, "logicalWidth"); val hgt = intField(info, "logicalHeight")
        val state = intField(info, "state")                 // Display.STATE_ON = 2
        val inner = minOf(w, hgt).toFloat() / maxOf(w, hgt) > 0.7f
        val now = SystemClock.uptimeMillis()
        if (state == 2 && (lastState != 2 || (inner && !lastInner))) onSince = now
        val swappedNow = inner && !lastInner
        val lastInnerBefore = lastInner
        if (inner && (!lastInner || (lastState != 2 && state == 2))) { swapAt = now; drawnAt = 0L }
        if (state != 2) offAt = now
        lastState = state; lastInner = inner
        trainStep(lastInnerBefore, swappedNow)
        curW = w; curH = hgt; curInner = inner; curState = state; curStack = intField(info, "layerStack")
        // idle: check every tick (cheap, nothing is drawn); while showing, vsync drives it and this is a watchdog
        if (!fxShown || SystemClock.uptimeMillis() - lastFrameAt > 50) render()
        if (fxShown && !vsyncOn) { vsyncOn = true; Choreographer.getInstance().postFrameCallback(frameCb) }
        val alpha = when {
            !inner -> 0f
            state != 2 -> 1f
            else -> {
                // hold black until: panel ON for HOLD ms AND our effect has drawn here (+2 frames to reach the
                // screen); never longer than MAX_HOLD after the swap (fail-open)
                val on = now - onSince
                val ready = drawnAt > 0 && now - drawnAt > 34
                val since = now - swapAt
                val holdEnd = if (since > MAX_HOLD) 0L else if (on >= HOLD && ready) maxOf(onSince + HOLD, drawnAt + 34) else -1L
                if (holdEnd < 0) 1f
                else if (holdEnd == 0L) (1f - (since - MAX_HOLD) / FADE.toFloat()).coerceIn(0f, 1f)
                else (1f - (now - holdEnd) / FADE.toFloat()).coerceIn(0f, 1f)
            }
        }
        applyBlack(alpha, curStack)
        if ((alpha == 1f || alpha == 0f) && alpha != loggedAlpha) {
            loggedAlpha = alpha
            Log.d(TAG, "black=${alpha.toInt()} inner=$inner state=$state on=${now - onSince}ms swap+${now - swapAt}ms drawn=${if (drawnAt > 0) "${now - drawnAt}ms ago" else "no"}")
        }
        st = "inner=$inner state=$state black=${"%.2f".format(alpha)} render=${if (renderOk) (if (fxShown) "drawing" else "idle") else "OFF"}"
    }

    private fun render() {
        lastFrameAt = SystemClock.uptimeMillis()
        val r = fxr ?: return; val hr = renderer ?: return; val sc = fxLayer ?: return
        if (!enabled || !renderOk || curW == 0) { hideFx(); return }
        r.fx = fx; r.slide = slide; r.coverMs = coverMs; r.innerMs = innerMs; r.finMode = finMode; r.finMs = finMs; r.softBlur = softBlur; r.softWobble = softWobble
        val a = smoother.tick()
        // armed while the panel is off or just woken (the effect covers the panel already), inner shape only so the
        // cover's AOD / lock screen is never covered, and only while the hinge moves: the inner panel sleeping or
        // waking with the phone lying open is not an open, so nothing is drawn
        val armed = curInner && (curState != 2 || SystemClock.uptimeMillis() - offAt < 500) && smoother.quietFor() < QUIET_S
        val canvas = node.beginRecording(size, size)
        val show = try { r.draw(canvas, curW, curH, curInner, a, armed, preview = false, innerLit = curInner && curState == 2) } finally { node.endRecording() }
        val t = SurfaceControl.Transaction()
        var changed = false
        if (curStack != fxStack) { setStack.invoke(t, sc, curStack); fxStack = curStack; changed = true }
        if (curW != fxW || curH != fxH) { setCrop.invoke(t, sc, curW, curH); fxW = curW; fxH = curH; changed = true }
        if (show) {
            hr.createRenderRequest().setVsyncTime(System.nanoTime()).syncAndDraw()
            if (curInner && drawnAt == 0L) drawnAt = SystemClock.uptimeMillis()
        }
        if (show != fxShown) { if (fxShown && !show && curInner) cap.postDelayed({ capture(false) }, 1500)
            t.setVisibility(sc, show); fxShown = show; changed = true; Log.d(TAG, "effect layer ${if (show) "ON" else "off"} inner=$curInner ${curW}x$curH") }
        if (changed) t.apply()
    }

    private fun applyBlack(alpha: Float, stack: Int) {
        if (alpha <= 0f && black == null) return
        val t = SurfaceControl.Transaction()
        if (black == null) {
            val b = SurfaceControl.Builder().setName("FoldDuo handoff black").setHidden(true)
            colorLayer.invoke(b)
            black = b.build()
            skipShot.invoke(t, black, true)
            setColor.invoke(t, black, floatArrayOf(0f, 0f, 0f))
            setCrop.invoke(t, black, size, size)
            t.setLayer(black!!, Int.MAX_VALUE - 5)
            blackStack = -1; blackAlpha = -1f
        }
        val l = black!!
        if (stack != blackStack) { setStack.invoke(t, l, stack); blackStack = stack }
        if (alpha != blackAlpha) { t.setAlpha(l, alpha); t.setVisibility(l, alpha > 0f); blackAlpha = alpha }
        t.apply()
    }

    private fun hideFx() {
        val l = fxLayer ?: return
        if (!fxShown) return
        try { SurfaceControl.Transaction().setVisibility(l, false).apply() } catch (_: Throwable) {}
        fxShown = false
    }

    private fun hideAll() {
        hideFx()
        val l = black ?: return
        try { SurfaceControl.Transaction().setVisibility(l, false).apply() } catch (_: Throwable) {}
        blackAlpha = 0f
    }

    override fun setEnabled(on: Boolean) { enabled = on; if (on) h.post { wake() } }
    override fun status(): String = st
    override fun innerDrawn() { /* unused, kept for the AIDL interface */ }
    override fun setFx(fx: Int, slide: Boolean) { this.fx = fx.coerceIn(0, EFFECTS.size - 1); this.slide = slide }
    override fun rendering(): Boolean = renderOk
    override fun startTraining(opens: Int) {
        trainCover.clear(); tStartNs = 0L
        trainWant = opens.coerceIn(1, 30); trainResult = "0/$trainWant"
    }
    override fun training(): String = trainResult
    override fun grantApp(pkg: String): String {
        if (pkg != "com.vibecodedstudio.nightfold") return "refused"
        val cmds = listOf(
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        )
        return cmds.joinToString("; ") { c ->
            try {
                val p = ProcessBuilder("sh", "-c", c).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                "${c.substringBefore(" $pkg")}=${p.waitFor()}${if (out.isNotEmpty()) " ($out)" else ""}"
            } catch (e: Throwable) { "$c failed: $e" }
        }.also { Log.d(TAG, "grantApp: $it") }
    }
    override fun setFinish(mode: Int, ms: Int) { finMode = mode.coerceIn(0, 3); finMs = ms.coerceIn(200, 3000) }
    // ---- non-Samsung foldables: the app forwards Android's public hinge sensor ----
    override fun hingeFromApp(angle: Float, tsNs: Long): Boolean {
        if (HingeReader.logReadings > 0) return false
        if (HingeReader.feed(angle, tsNs)) h.post { wake() }
        return true
    }
    override fun logFeed(): Boolean = HingeReader.logReadings > 0

    // ---- home-screen captures (automatic + a button) ----
    // The effect draws what the screen will show; a fresh capture keeps it matching the real icons and wallpaper.
    // Only the HOME screen, unlocked, nothing of ours on top (our layers are skipped in screenshots anyway).
    private val capThread = HandlerThread("fd-capture").apply { start() }
    private val cap = Handler(capThread.looper)
    private val lastCap = HashMap<String, Long>()
    private val capTick = object : Runnable {
        override fun run() { if (enabled && ::getInfo.isInitialized) capture(false); cap.postDelayed(this, CAP_EVERY_MS) }
    }
    private var homePkg: String? = null
    private fun sh(c: String): String = try {
        val p = ProcessBuilder("sh", "-c", c).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    } catch (e: Throwable) { "" }

    override fun captureNow(): String = capture(true)

    @Synchronized private fun capture(force: Boolean): String {
        // read the display now: cur* stop updating while the helper idles (hinge still), so they can say "on" for a
        // screen that has since gone off, and without a lock screen that would save a black picture
        val info = try { getInfo.invoke(dmg, 0) } catch (_: Throwable) { null } ?: return "no display info"
        val dw = intField(info, "logicalWidth"); val dh = intField(info, "logicalHeight")
        val inner = minOf(dw, dh).toFloat() / maxOf(dw, dh) > 0.7f
        val panel = if (inner) "inner" else "cover"
        val now = SystemClock.uptimeMillis()
        if (!force && now - (lastCap[panel] ?: -CAP_MIN_GAP_MS) < CAP_MIN_GAP_MS) return "recent"
        if (intField(info, "state") != 2) return "screen off"
        if (fxShown) return "effect showing"
        val home = homePkg ?: sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lines().lastOrNull { it.contains('/') }?.substringBefore('/')?.trim().also { homePkg = it }
        if (home.isNullOrEmpty()) return "no home app"
        val win = sh("dumpsys window | grep -E 'mCurrentFocus|isKeyguardShowing'")
        if (win.contains("isKeyguardShowing=true")) return "locked"
        if (!win.lines().any { it.contains("mCurrentFocus") && it.contains(home) }) return "home screen not showing"
        val dir = File("/sdcard/Android/data/com.vibecodedstudio.nightfold/files").apply { mkdirs() }
        val tmp = File(dir, "cap_tmp.png")
        val ids = Regex("""Display (\d+) """).findAll(sh("dumpsys SurfaceFlinger --display-id")).map { it.groupValues[1] }.toList()
        for (id in ids) {
            tmp.delete()
            sh("screencap -d $id -p ${tmp.path}")
            val wh = pngSize(tmp) ?: continue
            // the lit panel is the one with the current display's size (Galaxy Z Fold: inner 2448x1848, cover 1248x1972)
            if ((wh.first == dw && wh.second == dh) || (wh.first == dh && wh.second == dw)) {
                if (!tmp.renameTo(File(dir, "$panel.png"))) return "save failed"
                // the shell creates it -rw-rw----, which the app (its preview + fallback overlay) can't read;
                // make it readable like the original pictures
                sh("chmod 666 ${File(dir, "$panel.png").path}")
                lastCap[panel] = now
                h.post { fxr?.setPics(Pic.load(File(dir, "cover.png"), 624, 986, 210), Pic.load(File(dir, "inner.png"), 1224, 924, 20)) }
                Log.d(TAG, "captured $panel home screen (display $id, ${wh.first}x${wh.second})")
                return panel
            }
        }
        tmp.delete()
        return "capture failed"
    }
    private fun pngSize(f: File): Pair<Int, Int>? = try {
        f.inputStream().use { s ->
            val b = ByteArray(24); if (s.read(b) < 24) return null
            fun i(o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)
            Pair(i(16), i(20))
        }
    } catch (e: Throwable) { null }

    override fun setSoft(blurTenths: Int, wobble: Int) { softBlur = blurTenths.coerceIn(0, 30) / 10f; softWobble = wobble.coerceIn(0, 10).toFloat() }
    override fun setTiming(coverMs: Int, innerMs: Int) { this.coverMs = coverMs.coerceIn(50, 2000); this.innerMs = innerMs.coerceIn(50, 2500) }
    override fun destroy() {
        try {
            val t = SurfaceControl.Transaction()
            black?.let { t.setVisibility(it, false).reparent(it, null) }
            fxLayer?.let { t.setVisibility(it, false).reparent(it, null) }
            t.apply()
            renderer?.destroy()
        } catch (_: Throwable) {}
        exitProcess(0)
    }

    companion object {
        const val TAG = "FoldDuoHelper"
        const val HOLD = 150L
        const val FADE = 120L
        const val MAX_HOLD = 600L
        const val CAP_EVERY_MS = 5 * 60_000L     // light check while a screen is on
        const val CAP_MIN_GAP_MS = 10 * 60_000L  // automatic captures per screen at most this often
        const val QUIET_S = 1.5f            // hinge still this long: stop polling, no arming
    }
}
