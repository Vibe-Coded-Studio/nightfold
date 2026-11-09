package com.vibecodedstudio.nightfold

import android.content.Context
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GPU pipeline test: draws every visible effect once per case off screen (inner + cover shape, solid and
 * see-through like the end fade) and logs a marker around each draw, so a driver 'Pipeline create failed' line in
 * logcat can be matched to the effect and case that caused it. Started from adb only (Settings.Global needs shell):
 *   adb shell settings put global nightfold_gputest 1
 * Results: logcat tag NightfoldGpu, interleaved with AdrenoVK lines from the same pid.
 */
object GpuTest {
    /**
     * Pre-builds [fx]'s GPU pipelines off screen (the solid draw and the see-through fade draw), so the first open
     * after a start or an effect switch does not pay for the build. The Fold's Adreno logs one 'Pipeline create
     * failed' per new pipeline while building it (fast path fails, fallback works; frames are right: GpuTest 11-08),
     * so that line now shows up here instead of during an open. Tiny pictures: the pipeline does not depend on size.
     */
    fun warm(fx: Int, log: (String) -> Unit) {
        val t0 = android.os.SystemClock.uptimeMillis()
        val th = HandlerThread("gpuwarm").apply { start() }
        val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        var latch = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close(); latch.countDown() }, Handler(th.looper))
        val node = RenderNode("gpuwarm").apply { setPosition(0, 0, 64, 64) }
        val hr = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node); isOpaque = false }
        try {
            val pic = { Pic(listOf(android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888))) }
            val r = FxRenderer(pic(), pic()).apply { this.fx = fx; finMode = 3 }
            for (alpha in listOf(255, 120)) {
                latch = CountDownLatch(1)
                val c = node.beginRecording(64, 64)
                try { r.draw(c, 64, 48, true, 120f, false, preview = true, previewFin = 0.5f, previewProg = 0.5f, testAlpha = alpha) }
                finally { node.endRecording() }
                hr.createRenderRequest().setVsyncTime(System.nanoTime()).syncAndDraw()
                latch.await(3, TimeUnit.SECONDS)
            }
            log("gpu warm: ${EFFECTS[fx].replace(Regex("\\s+"), " ")} ready in ${android.os.SystemClock.uptimeMillis() - t0} ms")
        } finally { hr.destroy(); reader.close(); th.quitSafely() }
    }

    private const val TAG = "NightfoldGpu"
    @Volatile private var running = false

    fun run(ctx: Context) {
        if (running) return
        running = true
        Thread {
            val th = HandlerThread("gputest").apply { start() }
            try { go(ctx, Handler(th.looper)) } catch (e: Throwable) { Log.e(TAG, "test crashed", e) }
            finally { th.quitSafely(); running = false }
        }.start()
    }

    private fun go(ctx: Context, h: Handler) {
        val w = 1224; val hgt = 1224
        val reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        var latch = CountDownLatch(1)
        var lit = -1f
        // how much of the drawn area has any colour: 0 = the draw was dropped (only the black clear is there)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.let { im ->
                try {
                    val hb = im.hardwareBuffer
                    val b = hb?.let { android.graphics.Bitmap.wrapHardwareBuffer(it, null)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
                    if (b != null) {
                        var n = 0; var on = 0
                        for (y in 20 until 900 step 40) for (x in 20 until 600 step 40) { n++; val p = b.getPixel(x, y)
                            if (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255) > 30) on++ }
                        lit = on.toFloat() / n
                    }
                    hb?.close()
                } finally { im.close() }
            }
            latch.countDown()
        }, h)
        val node = RenderNode("gputest").apply { setPosition(0, 0, w, hgt) }
        val hr = HardwareRenderer().apply { setSurface(reader.surface); setContentRoot(node); isOpaque = false }
        val cov = Pic.load(ctx, "cover.png", 624, 986, 210); val inn = Pic.load(ctx, "inner.png", 1224, 924, 20)
        fun desc(n: String, p: Pic) = p.levels.joinToString { "${it.width}x${it.height} ${it.config} ${it.colorSpace?.name} alpha=${it.hasAlpha()}" }.let { "$n: $it" }
        Log.i(TAG, desc("cover pic", cov)); Log.i(TAG, desc("inner pic", inn))
        val r = FxRenderer(cov, inn)
        r.finMode = 3
        Log.i(TAG, "start")
        var timeouts = 0
        // inner screen shape vs cover shape, inner vs cover picture, finish pass vs fill, each drawn twice
        data class Case(val name: String, val innerShape: Boolean, val pics: Pair<Pic, Pic>, val fin: Float, val arrivingFix: Boolean = false)
        val cases = listOf(
            Case("inner-shape innerPic fin", true, cov to inn, 0.5f),
            Case("inner-shape innerPic fill", true, cov to inn, -1f),
            Case("inner-shape coverPic fin", true, cov to cov, 0.5f),
            Case("inner-shape coverPic fill", true, cov to cov, -1f),
            Case("cover-shape coverPic", false, cov to inn, -1f),
            Case("cover-shape innerPic", false, inn to inn, -1f))
        for (fx in listOf(7, 32, 0)) for (cs in cases) for (rep in 1..2) {
            r.fx = fx; r.setPics(cs.pics.first, cs.pics.second)
            val (dw, dh) = if (cs.innerShape) 1224 to 924 else 624 to 986
            val case = "fx=${EFFECTS[fx].replace(Regex("\\s+"), " ")} ${cs.name} #$rep"
            Log.i(TAG, "BEGIN $case")
            latch = CountDownLatch(1); lit = -1f
            val c = node.beginRecording(w, hgt)
            try { r.draw(c, dw, dh, cs.innerShape, 120f, false, preview = true, previewFin = cs.fin,
                previewProg = 0.5f, testAlpha = 255) } finally { node.endRecording() }
            hr.createRenderRequest().setVsyncTime(System.nanoTime()).syncAndDraw()
            if (!latch.await(3, TimeUnit.SECONDS)) timeouts++
            Log.i(TAG, "END $case lit=${"%.3f".format(lit)}")
        }
        hr.destroy(); reader.close()
        Log.i(TAG, "done (frames that never arrived: $timeouts)")
    }
}
