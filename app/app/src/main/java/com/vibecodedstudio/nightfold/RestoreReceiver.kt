package com.vibecodedstudio.nightfold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After an app update or a reboot, turn the effect back on if it was on (no "Effect on" tap needed). */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MY_PACKAGE_REPLACED && i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!c.getSharedPreferences("fx", Context.MODE_PRIVATE).getBoolean("enabled", false)) return
        try { c.startForegroundService(Intent(c, FoldService::class.java)) }
        catch (e: Throwable) { android.util.Log.w("FoldDuoT", "restore failed: $e") }
    }
}
