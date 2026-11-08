package com.vibecodedstudio.nightfold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Keeps a touch-through overlay on the main display (on a Galaxy Z Fold the cover and inner panels both appear as display 0,
 * the OS swaps which panel it is). The overlay is invisible (window alpha 0) unless the hinge is moving, so it never
 * blocks touches or costs anything at rest.
 */
const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
const val BuildConfigVersion = 13   // bump when HelperService changes: Shizuku restarts the helper

class FoldService : Service() {
    companion object {
        @Volatile var helper: IHelper? = null
        @Volatile var helperState = "off"
        @Volatile var running = false
            private set(v) { field = v; android.os.Handler(android.os.Looper.getMainLooper()).post { onRunningChanged?.invoke() } }
        /** The settings screen listens here so its switch shows the real state once the service has actually
         *  started or stopped (startForegroundService returns before onStartCommand runs). Main thread. */
        var onRunningChanged: (() -> Unit)? = null
    }
    private var destroyed = false
    private var wm: WindowManager? = null
    private var view: FxView? = null
    private var lp: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // Shizuku started later (e.g. after a reboot): connect the helper as soon as it is up
    private val shizukuUp = rikka.shizuku.Shizuku.OnBinderReceivedListener { handler.post { hideStartShizuku(); connectHelper() } }
    private val shizukuDown = rikka.shizuku.Shizuku.OnBinderDeadListener { handler.removeCallbacks(checkLater); handler.postDelayed(checkLater, 3000) }
    override fun onCreate() {
        super.onCreate()
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(shizukuUp)
            rikka.shizuku.Shizuku.addBinderDeadListener(shizukuDown)
        } catch (_: Throwable) {}
        // after a reboot give Shizuku a moment (it may start itself) before asking the user
        handler.postDelayed(checkLater, 15000)
    }
    private val checkLater = Runnable { checkShizuku() }

    /** Shizuku down = the simple fallback is drawing: a quiet notification offers the full effect. */
    private fun checkShizuku() {
        if (destroyed) return
        val up = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (up) { hideStartShizuku(); return }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shizuku", "Start Shizuku reminder", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val open = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) ?: return
        val pi = android.app.PendingIntent.getActivity(this, 2, open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, Notification.Builder(this, "shizuku")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Start Shizuku for the full fold effect")
            .setContentText("Tap to open Shizuku, then tap Start. Nightfold reconnects by itself.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
    private fun hideStartShizuku() { getSystemService(NotificationManager::class.java).cancel(2) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        running = true
        val helperDraws = try { helper?.rendering() == true } catch (_: Throwable) { false }
        if (!helperDraws) AppHinge.start(this)   // fallback only; the helper reads the fine hinge itself (no log popup)
        if (view == null && !helperDraws) addOverlay()
        applyPrefs()
        connectHelper()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fx", "Fold effect", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "fx").setContentTitle("Nightfold is on")
            .setSmallIcon(android.R.drawable.ic_menu_rotate).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun addOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) { android.util.Log.w("FoldDuoT", "no overlay permission yet"); return }
        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val wctx = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val w = wctx.getSystemService(WindowManager::class.java)
        val v = FxView(wctx, preview = false)
        val side = panelSide(w)
        val p = WindowManager.LayoutParams(
            side, side,          // bigger than both panels: no resize at the swap (see panelSide)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        p.setFitInsetsTypes(0)
        p.alpha = 0f
        p.title = "FoldDuoFX"
        p.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        p.x = 0; p.y = 0
        v.onActiveChanged = { on -> setSolid(on) }
        v.onTooSmall = { need -> growOverlay(need) }
        w.addView(v, p)
        wm = w; view = v; lp = p; solid = false
        handler.post(safety)
        val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenRx, f)
        applyPrefs()
        android.util.Log.d("FoldDuoT", "app overlay added (fallback)")
    }

    /** Overlay side: the biggest panel this phone has shown (saved), at least the current one and 2448 (Z Fold's
     *  inner). An app can't list the panels that are off, so a bigger one seen later grows it once (growOverlay). */
    private fun panelSide(w: WindowManager): Int {
        val b = w.maximumWindowMetrics.bounds
        val sp = getSharedPreferences("fx", Context.MODE_PRIVATE)
        return maxOf(2448, sp.getInt("panelMax", 0), b.width(), b.height())
    }
    private fun growOverlay(need: Int) {
        val v = view ?: return; val p = lp ?: return
        if (need <= p.width) return
        getSharedPreferences("fx", Context.MODE_PRIVATE).edit().putInt("panelMax", need).apply()
        p.width = need; p.height = need
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        android.util.Log.d("FoldDuoT", "overlay grown to $need px")
    }

    private fun removeOverlay() {
        handler.removeCallbacks(safety)
        try { unregisterReceiver(screenRx) } catch (_: Exception) {}
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        if (view != null) android.util.Log.d("FoldDuoT", "app overlay removed (helper draws)")
        view = null; solid = false
    }

    private val screenRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val v = view ?: return
            android.util.Log.d("FoldDuoT", "broadcast ${i?.action}")
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { v.armForever(); setSolid(true) }
                Intent.ACTION_SCREEN_ON -> { solidSince = android.os.SystemClock.uptimeMillis(); v.armFor(500) }
            }
        }
    }

    /**
     * Android caps a touch-through (FLAG_NOT_TOUCHABLE) overlay at 80% opacity (SurfaceFlinger shows alpha=0.8),
     * which lets the real screen show through. So while the effect plays the window is fully opaque
     * and takes touches; at rest it is invisible and touch-through again. Safety: never solid for more than 3 s.
     */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var solid = false
    private var solidSince = 0L
    private fun setSolid(on: Boolean) {
        val v = view ?: return; val p = lp ?: return
        if (on == solid) return
        solid = on
        if (on) solidSince = android.os.SystemClock.uptimeMillis()
        p.alpha = if (on) 1f else 0f
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                  else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        android.util.Log.d("FoldDuoT", "solid=$on")
    }
    private val safety = object : Runnable {
        override fun run() {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (solid && pm.isInteractive && android.os.SystemClock.uptimeMillis() - solidSince > 3000) {
                android.util.Log.w("FoldDuoT", "safety: overlay solid > 3 s, releasing")
                setSolid(false)
            }
            handler.postDelayed(this, 300)
        }
    }

    // ---- Shizuku helper (shell user: draws the effect and the black handoff layer) ----
    private val helperArgs by lazy {
        rikka.shizuku.Shizuku.UserServiceArgs(android.content.ComponentName(packageName, HelperService::class.java.name))
            .daemon(false).processNameSuffix("helper").debuggable((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0).version(BuildConfigVersion)
    }
    private val helperConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(n: android.content.ComponentName?, b: IBinder?) {
            helper = IHelper.Stub.asInterface(b); helper?.setEnabled(true)
            // one Shizuku allow covers everything: the shell helper grants the app's own permissions (hinge log,
            // overlay fallback, notification). Off the main thread: pm takes a moment.
            val hp = helper
            Thread { try { android.util.Log.d("FoldDuoT", "grants: ${hp?.grantApp(packageName)}") } catch (e: Throwable) {} }.start()
            applyPrefs()
            android.util.Log.d("FoldDuoT", "helper connected: ${helper?.status()}")
            // the helper draws the effect itself (above Android's panel-switch layer): drop our own overlay.
            // It takes a moment to set up its renderer, so ask again shortly.
            handler.postDelayed({ useHelperIfRendering() }, 300)
            handler.postDelayed({ useHelperIfRendering() }, 1500)
        }
        override fun onServiceDisconnected(n: android.content.ComponentName?) {
            helper = null
            // after onDestroy (our own unbind kills the helper) this callback still arrives: adding the overlay
            // then would leak a window that nothing removes and that goes solid at every screen-off
            if (destroyed) return
            android.util.Log.w("FoldDuoT", "helper gone: back to the app overlay")
            AppHinge.start(this@FoldService)
            if (view == null) addOverlay()
        }
    }
    private fun connectHelper() {
        try {
            if (helper != null) { helper?.setEnabled(true); return }
            if (!rikka.shizuku.Shizuku.pingBinder()) { helperState = "Shizuku not running"; return }
            if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                helperState = "Shizuku permission needed"; return
            }
            rikka.shizuku.Shizuku.bindUserService(helperArgs, helperConn)
            helperState = "connecting"
        } catch (e: Throwable) { helperState = "helper error: $e" }
    }

    private fun useHelperIfRendering() {
        val ok = try { helper?.rendering() == true } catch (_: Throwable) { false }
        android.util.Log.d("FoldDuoT", "helper rendering=$ok")
        if (ok && view != null) removeOverlay()
        if (ok) {
            // the helper reads Samsung's fine hinge log itself; without one (other foldables) it gets the public sensor
            val hasLog = try { helper?.logFeed() == true } catch (_: Throwable) { false }
            if (hasLog) AppHinge.stop() else {
                AppHinge.start(this)
                AppHinge.forward = { a, ts -> try { helper?.hingeFromApp(a, ts) == true } catch (_: Throwable) { false } }
            }
        }
    }

    fun applyPrefs() {
        val sp = getSharedPreferences("fx", Context.MODE_PRIVATE)
        view?.fx = savedFx(sp)
        view?.slide = sp.getBoolean("slide", true)
        val cm = sp.getInt("coverMs", DEFAULT_COVER_MS); val im = sp.getInt("innerMs", DEFAULT_INNER_MS)
        view?.timing(cm, im)
        val fm = sp.getInt("finMode", 0); val fms = sp.getInt("finMs", DEFAULT_FIN_MS)
        val sb = sp.getInt("softBlur", (DEFAULT_SOFT_BLUR * 10).toInt())
        val sw = sp.getInt("softWobble", 0)
        view?.finish(fm, fms, sb / 10f, sw.toFloat())
        try { helper?.setFx(savedFx(sp), sp.getBoolean("slide", true)); helper?.setTiming(cm, im); helper?.setFinish(fm, fms); helper?.setSoft(sb, sw) } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(safety); handler.removeCallbacks(checkLater)
        try { rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuUp); rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuDown) } catch (_: Throwable) {}
        hideStartShizuku()
        try { helper?.setEnabled(false); rikka.shizuku.Shizuku.unbindUserService(helperArgs, helperConn, true) } catch (_: Throwable) {}
        helper = null
        removeOverlay()
        AppHinge.stop(); running = false
        super.onDestroy()
    }
}
