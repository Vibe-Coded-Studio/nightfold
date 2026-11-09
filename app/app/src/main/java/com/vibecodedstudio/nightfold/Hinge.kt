package com.vibecodedstudio.nightfold

import android.os.SystemClock
import android.util.Log
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Hinge angle from Samsung's sensors-hal log (measured on a Galaxy Z Fold: ~11 deg steps every 40 ms on a normal
 * open). Read only in the Shizuku helper, which runs as the shell user and so gets no log-access prompt. The app
 * process never runs logcat (Android 13+ asks "allow access to all device logs?" each time an app does). Phones without
 * Samsung's log get Android's public hinge sensor from the app instead (AppHinge -> hingeFromApp, coarse: ~0/90/180).
 */
object HingeReader {
    private const val TAG = "FoldDuoHinge"
    private val FA = Regex("""\]folding_angle ts=(\d+) ns value \[\s*(-?\d+)/""")

    @Volatile var lastTsNs = 0L; private set
    @Volatile var lastAngle = -1f; private set
    @Volatile var prevTsNs = 0L; private set
    @Volatile var prevAngle = -1f; private set
    @Volatile var running = false; private set
    @Volatile var linesSeen = 0; private set
    /** Readings from Samsung's log. 0 on phones that do not log it; the app then forwards the public sensor. */
    @Volatile var logReadings = 0; private set

    /** Called on the reader thread after each new reading, so the helper can sleep while the hinge is still
     *  instead of polling. */
    @Volatile var onReading: (() -> Unit)? = null

    private var thread: Thread? = null

    /** All four values from the same reading (the reader thread can feed between separate field reads). */
    class Snap(val ts: Long, val a: Float, val pts: Long, val pa: Float)
    @Synchronized fun snap() = Snap(lastTsNs, lastAngle, prevTsNs, prevAngle)

    fun start() {
        if (thread?.isAlive == true) return
        running = true
        thread = Thread({
            while (running) {
                var p: Process? = null
                try {
                    p = ProcessBuilder("logcat", "-v", "raw", "-T", "1", "-s", "sensors-hal:I").redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) {
                            if (!running) break
                            linesSeen++
                            val m = FA.find(l) ?: continue
                            val ts = m.groupValues[1].toLong()
                            val a = m.groupValues[2].toFloat()
                            if (!feed(a, ts)) continue
                            logReadings++
                            onReading?.invoke()
                            Log.d("FoldDuoT", "reading $a deg, late ${(SystemClock.elapsedRealtimeNanos() - ts) / 1_000_000} ms")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "logcat reader: $e")
                } finally { p?.destroy() }      // never leave a logcat process behind
                if (running) Thread.sleep(1000)
            }
        }, "hinge-reader").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    /** one new reading (ts = elapsedRealtimeNanos base); false if it is not newer than the last one */
    @Synchronized fun feed(a: Float, ts: Long): Boolean {
        if (ts <= lastTsNs) return false
        prevTsNs = lastTsNs; prevAngle = lastAngle
        lastTsNs = ts; lastAngle = a
        return true
    }
}

/** Android's public hinge sensor in the app process, forwarded to the helper on phones without Samsung's hinge log.
 *  No permission, no popup; coarse steps. */
object AppHinge : android.hardware.SensorEventListener {
    private var sm: android.hardware.SensorManager? = null
    fun start(c: android.content.Context) {
        if (sm != null) return
        val m = c.getSystemService(android.hardware.SensorManager::class.java) ?: return
        val s = m.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE) ?: run { Log.w("FoldDuoT", "no public hinge sensor"); return }
        m.registerListener(this, s, android.hardware.SensorManager.SENSOR_DELAY_FASTEST); sm = m
    }
    fun stop() { sm?.unregisterListener(this); sm = null; forward = null }
    /** Set while the helper draws but has no log feed (non-Samsung foldables): readings go to it too. It returns
     *  false once the helper has its own finer feed, and the app then stops listening. */
    @Volatile var forward: ((Float, Long) -> Boolean)? = null
    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        val f = forward ?: return
        if (!f(e.values[0], e.timestamp)) stop()
    }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
}

/** Smooths the coarse readings: aims a little ahead along the measured speed, then eases. */
class HingeSmoother {
    var angle = -1f; private set
    private var vel = 0f
    private var usedTs = 0L
    private var lastFrameNs = 0L

    fun reset(to: Float) { angle = to; vel = 0f; usedTs = HingeReader.lastTsNs }

    fun tick(): Float {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastFrameNs == 0L) 0.016f else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNs = now
        val sn = HingeReader.snap()
        val ts = sn.ts; val a = sn.a
        if (a < 0f) return angle
        if (angle < 0f) angle = a
        if (ts != usedTs) {
            usedTs = ts
            val pts = sn.pts
            vel = if (pts > 0 && ts - pts < 700_000_000L) (a - sn.pa) / ((ts - pts) / 1e9f) else 0f
        }
        val since = max(0f, (now - ts) / 1e9f)
        var target = a
        if (since > 0.12f) {                     // sensor goes quiet at an end stop
            if (a > 165f) target = 180f
            if (a < 15f) target = 0f
        }
        val lead = vel * min(since, 0.16f) * max(0f, 1f - max(0f, since - 0.06f) / 0.1f)
        target = (target + lead).coerceIn(0f, 180f)
        angle += (target - angle) * (1f - exp(-dt / 0.035f))
        return angle
    }

    /** seconds since the last real reading */
    fun quietFor(): Float = (SystemClock.elapsedRealtimeNanos() - HingeReader.lastTsNs) / 1e9f
}
