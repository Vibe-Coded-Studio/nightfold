package com.vibecodedstudio.nightfold

import android.content.SharedPreferences

/**
 * Per-effect settings. The plain keys (coverMs, innerMs, finMode, finMs, softBlur, softWobble) are the shared
 * default that every untouched effect follows; moving a slider or Finish button on an effect saves "key@fx" for that
 * effect only. Training writes the shared cover speed, so effects with their own cover speed keep it.
 */
object FxSettings {
    val KEYS = listOf("coverMs", "innerMs", "finMode", "finMs", "softBlur", "softWobble")

    fun def(key: String): Int = when (key) {
        "coverMs" -> DEFAULT_COVER_MS
        "innerMs" -> DEFAULT_INNER_MS
        "finMs" -> DEFAULT_FIN_MS
        "softBlur" -> (DEFAULT_SOFT_BLUR * 10).toInt()
        else -> 0
    }

    /** the shared default for [key] (the trained cover speed when there is one) */
    fun shared(sp: SharedPreferences, key: String): Int =
        sp.getInt(key, if (key == "coverMs") sp.getInt("trained_coverMs", def(key)) else def(key))

    fun get(sp: SharedPreferences, fx: Int, key: String): Int =
        if (sp.contains("$key@$fx")) sp.getInt("$key@$fx", 0) else shared(sp, key)

    fun put(sp: SharedPreferences, fx: Int, key: String, v: Int) = sp.edit().putInt("$key@$fx", v).apply()

    fun custom(sp: SharedPreferences, fx: Int) = KEYS.any { sp.contains("$it@$fx") }

    fun reset(sp: SharedPreferences, fx: Int) = sp.edit().apply { KEYS.forEach { remove("$it@$fx") } }.apply()
}
