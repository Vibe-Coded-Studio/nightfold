package com.vibecodedstudio.nightfold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the Shizuku helper (HelperService) connected; the helper draws the effect. Shizuku is required (decided
 * 11-09): the old app-overlay fallback sat under Samsung's panel-switch layer (home screen flashed ~300 ms), was capped
 * at 80% opacity and redrew every frame forever. Without Shizuku the effect is off and a notification asks for it.
 */
const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
const val BuildConfigVersion = 40   // bump when HelperService changes: Shizuku restarts the helper

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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

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
        contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(GPU_TEST_KEY), false, gpuTestObs)
        // the cover's picture is captured on its own a moment after the cover is unlocked (11-09, his ask):
        // only the app can hear the unlock, the helper does the capture
        registerReceiver(unlockRx, android.content.IntentFilter(Intent.ACTION_USER_PRESENT))
    }
    // GPU pipeline test (GpuTest), started from adb: 'settings put global nightfold_gputest 1' (only shell can write it)
    private val gpuTestObs = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (android.provider.Settings.Global.getString(contentResolver, GPU_TEST_KEY) == "1") GpuTest.run(this@FoldService)
        }
    }
    private val checkLater = Runnable { checkShizuku() }

    /** Shizuku down = no effect: a quiet notification asks for it. */
    private fun checkShizuku() {
        if (destroyed) return
        val up = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (up) { hideStartShizuku(); if (helper == null) connectHelper(); return }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shizuku", "Start Shizuku reminder", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        val open = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) ?: return
        val pi = android.app.PendingIntent.getActivity(this, 2, open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2, Notification.Builder(this, "shizuku")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Start Shizuku to turn the fold effect on")
            .setContentText("Tap to open Shizuku, then tap Start. Nightfold reconnects by itself.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }
    private fun hideStartShizuku() { getSystemService(NotificationManager::class.java).cancel(2) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // the notification's Stop button (Play: the user must be able to stop a foreground service): same as the switch
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("fx", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        applyPrefs()
        connectHelper()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fx", "Fold effect", NotificationManager.IMPORTANCE_MIN))
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        val open = android.app.PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val stop = android.app.PendingIntent.getService(this, 4, Intent(this, FoldService::class.java).setAction(ACTION_STOP), flags)
        val n = Notification.Builder(this, "fx").setContentTitle("Nightfold is on")
            .setContentText("Plays the fold animation when you open or close your phone")
            .setSmallIcon(android.R.drawable.ic_menu_rotate).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    // ---- Shizuku helper (shell user: draws the effect and the black handoff layer) ----
    private val helperArgs by lazy {
        rikka.shizuku.Shizuku.UserServiceArgs(android.content.ComponentName(packageName, HelperService::class.java.name))
            .daemon(false).processNameSuffix("helper").debuggable((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0).version(BuildConfigVersion)
    }
    private val helperConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(n: android.content.ComponentName?, b: IBinder?) {
            helper = IHelper.Stub.asInterface(b); helper?.setEnabled(true)
            // notifications: the app asks with the normal Android prompt (0.3.0); the helper grants nothing
            applyPrefs()
            android.util.Log.d("FoldDuoT", "helper connected: ${helper?.status()}")
            // it takes a moment to set up its renderer and hinge feed, so ask shortly
            handler.postDelayed({ useHelperIfRendering() }, 300)
            handler.postDelayed({ useHelperIfRendering() }, 1500)
        }
        override fun onServiceDisconnected(n: android.content.ComponentName?) {
            helper = null
            AppHinge.stop()
            if (destroyed) return
            // no fallback any more: the effect is off until the helper is back (Shizuku restarted -> shizukuUp)
            helperState = "helper stopped"
            android.util.Log.w("FoldDuoT", "helper gone: effect off until Shizuku is back")
            handler.removeCallbacks(checkLater); handler.postDelayed(checkLater, 3000)
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
        // the selected effect's own settings, or the shared default for the ones it has not changed (FxSettings)
        val f = savedFx(sp)
        fun g(k: String) = FxSettings.get(sp, f, k)
        val cm = g("coverMs"); val im = g("innerMs")
        val fm = g("finMode"); val fms = g("finMs")
        val sb = g("softBlur")
        val sw = g("softWobble")
        try { helper?.setCaptureAllowed(sp.getBoolean(CAP_CONSENT, false)) } catch (_: Throwable) {}
        try { helper?.setFx(savedFx(sp), sp.getBoolean("slide", true)); helper?.setTiming(cm, im); helper?.setFinish(fm, fms); helper?.setSoft(sb, sw) } catch (_: Throwable) {}
    }

    private val unlockRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { val hp = helper ?: return; Thread { try { hp.unlocked() } catch (_: Throwable) {} }.start() }
    }

    override fun onDestroy() {
        destroyed = true
        try { unregisterReceiver(unlockRx) } catch (_: Throwable) {}
        handler.removeCallbacks(checkLater)
        try { rikka.shizuku.Shizuku.removeBinderReceivedListener(shizukuUp); rikka.shizuku.Shizuku.removeBinderDeadListener(shizukuDown) } catch (_: Throwable) {}
        hideStartShizuku()
        contentResolver.unregisterContentObserver(gpuTestObs)
        try { helper?.setEnabled(false); rikka.shizuku.Shizuku.unbindUserService(helperArgs, helperConn, true) } catch (_: Throwable) {}
        helper = null
        AppHinge.stop(); running = false
        super.onDestroy()
    }
}

private const val GPU_TEST_KEY = "nightfold_gputest"
const val ACTION_STOP = "com.vibecodedstudio.nightfold.STOP"
/** "fx" pref: the user agreed to home-screen pictures (Play prominent disclosure, 0.3.0) */
const val CAP_CONSENT = "capConsent"
