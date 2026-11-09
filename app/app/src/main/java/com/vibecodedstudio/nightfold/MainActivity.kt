package com.vibecodedstudio.nightfold

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

private const val TRAIN_OPENS = 8     // median of 8 is steady; odd ones are dropped and replaced (HelperService)

/** Pick the effect, preview it with a slider, switch the effect on/off. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: FxView
    private lateinit var onSwitch: Switch
    private var settingSwitch = false

    private val PURPLE = Color.rgb(138, 92, 255)
    private val LILAC = Color.rgb(167, 123, 255)
    private val MUTED = Color.rgb(154, 147, 184)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun chip(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
        setPadding(dp(13), dp(6), dp(13), dp(6)); background = round(Color.argb(170, 42, 38, 64), 20)
        setOnClickListener { onClick(this) }
    }
    private fun caps(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.1f; setTextColor(MUTED); setPadding(0, dp(16), 0, dp(8))
    }
    private fun styleBar(b: SeekBar) {
        b.progressTintList = ColorStateList.valueOf(PURPLE); b.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        b.progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(60, 54, 90))
    }

    private var narrow = false
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if ((c.screenWidthDp < 600) != narrow) recreate()     // fold / unfold: swap between the two layouts
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        narrow = resources.configuration.screenWidthDp < 600
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("fx", Context.MODE_PRIVATE)
        window.statusBarColor = Color.rgb(11, 9, 18); window.navigationBarColor = Color.rgb(11, 9, 18)

        // eclipse theme: effect list on the left, preview + speed card on the right
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0.85f, 0f)
                gradientRadius = dp(900).toFloat(); colors = intArrayOf(Color.rgb(58, 31, 110), Color.rgb(11, 9, 18))
            }
        }

        // ---- left column ----
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(18), 0) }
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(2), Color.rgb(201, 168, 255)) }
            elevation = dp(8).toFloat(); outlineAmbientShadowColor = PURPLE; outlineSpotShadowColor = PURPLE
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        title.addView(TextView(this).apply { text = "Nightfold"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        side.addView(title)

        val onRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(18), 0, 0) }
        onRow.addView(TextView(this).apply { text = "Effect on"; textSize = 16f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        onSwitch = Switch(this).apply {
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(PURPLE, Color.rgb(60, 54, 90)))
            setOnCheckedChangeListener { _, on -> if (!settingSwitch) { if (on) turnOn() else turnOff() } }
        }
        onRow.addView(onSwitch)
        side.addView(onRow)
        status = TextView(this).apply { textSize = 12f; setTextColor(MUTED); setPadding(0, dp(4), 0, 0) }
        side.addView(status)

        val effects = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        effects.addView(caps("Effects"))
        // per-effect settings (FxSettings): picking an effect moves the sliders + Finish buttons to its values
        val fxRefresh = ArrayList<() -> Unit>()
        fun curFx() = savedFx(sp)
        // groups: Eclipse R-W, bloom-style AI-AP, X-AH, then K-Q, with a divider before each group
        val groupStart = FX_GROUPS.drop(1).map { it.first() }.toSet()
        val order = FX_GROUPS.flatten()
        val rows = HashMap<Int, TextView>()
        fun mark(sel: Int) = rows.forEach { (i, tv) ->
            val on = i == sel
            tv.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(107, 63, 216), Color.rgb(58, 36, 112)))
                .apply { cornerRadius = dp(12).toFloat() } else null
            tv.elevation = if (on) dp(6).toFloat() else 0f
            val (l, n) = EFFECTS[i].split(Regex("\\s+"), 2)
            val own = if (FxSettings.custom(sp, i)) "  •" else ""
            tv.text = android.text.SpannableString("$l   $n$own").apply {
                setSpan(android.text.style.ForegroundColorSpan(if (on) Color.WHITE else LILAC), 0, l.length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, l.length, 0)
                if (own.isNotEmpty()) setSpan(android.text.style.ForegroundColorSpan(LILAC), length - 1, length, 0)
            }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in order) {
            if (i in groupStart) list.addView(View(this).apply { setBackgroundColor(Color.argb(30, 255, 255, 255)) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
            val tv = TextView(this).apply {
                textSize = 16f; setTextColor(Color.WHITE); setPadding(dp(14), dp(10), dp(14), dp(10))
                outlineSpotShadowColor = PURPLE; outlineAmbientShadowColor = PURPLE
                setOnClickListener {
                    sp.edit().putInt("fx2", i).apply(); preview.fx = i; mark(i)
                    fxRefresh.forEach { it() }; previewFinish(); restartService()
                    // picking an effect plays it in the preview straight away
                    preview.slowMo = false; preview.playStart = android.os.SystemClock.uptimeMillis()
                }
            }
            rows[i] = tv; list.addView(tv)
        }
        mark(savedFx(sp))
        effects.addView(list)

        // ---- right column: preview card ----
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = FrameLayout(this).apply {
            background = round(Color.BLACK, 18); clipToOutline = true
        }
        preview = FxView(this).apply {
            fx = savedFx(sp); this.slide = sp.getBoolean("slide", true); manualAngle = 120f
        }
        previewFinish()
        card.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val angleLbl = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), dp(6), dp(12), dp(6)); background = round(Color.argb(150, 0, 0, 0), 14)
            text = "120°  ·  drag to fold"
        }
        card.addView(angleLbl, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        preview.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
                preview.playStart = -1L
                val a = (e.x / v.width).coerceIn(0f, 1f) * 180f
                preview.manualAngle = a; angleLbl.text = "${a.toInt()}°"
            }
            true
        }
        preview.onPreviewAngle = { a -> angleLbl.text = "${a.toInt()}°" }
        val playRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun gap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
        playRow.addView(chip("▶  Play") { preview.slowMo = false; preview.playStart = android.os.SystemClock.uptimeMillis() }, gap())
        playRow.addView(chip("Slow ×4") { preview.slowMo = true; preview.playStart = android.os.SystemClock.uptimeMillis() }, gap())
        playRow.addView(chip("Inner screen") { c -> preview.previewInner = !preview.previewInner; c.text = if (preview.previewInner) "Inner screen" else "Cover screen" }, gap())
        card.addView(playRow, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), dp(14), dp(14), dp(14)) })

        // ---- speed card ----
        val sc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(10))
            background = round(Color.argb(10, 255, 255, 255), 18, Color.argb(20, 255, 255, 255))
        }
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sc.addView(cols)
        // opening play speed: how long each half takes, in ms
        fun speedSlider(label: String, key: String, def0: Int, lo: Int, hi: Int, into: LinearLayout = cols, step: Int = 10,
                        fmt: (Int) -> String = { "$it ms" }) {
            val def = FxSettings.shared(sp, key)     // the shared default (cover: the trained "your normal open")
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
            row.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 13f; isSingleLine = true }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = TextView(this).apply { setTextColor(MUTED); textSize = 12f; isSingleLine = true }
            row.addView(v)
            val normal = if (key == "coverMs" && sp.contains("trained_coverMs")) "your normal" else "default"
            // up to three sliders share a row: just the number, plus a mark when it is the normal/default value
            fun show(x: Int) { v.text = fmt(x) + (if (x == def) " ✓" else "") }
            val cur = FxSettings.get(sp, curFx(), key); show(cur)
            col.addView(row)
            into.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            val bar = SeekBar(this)
            fxRefresh.add { val c = FxSettings.get(sp, curFx(), key); bar.progress = (c - lo) / step; show(c) }
            col.addView(bar.apply {
                styleBar(this); setPadding(dp(6), dp(4), dp(6), dp(4))
                max = (hi - lo) / step; progress = (cur - lo) / step
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, u: Boolean) { show(lo + p * step) }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) {
                        FxSettings.put(sp, curFx(), key, lo + (b?.progress ?: 0) * step)
                        mark(curFx()); previewFinish(); restartService()
                    }
                })
            })
        }
        speedSlider("Cover speed", "coverMs", DEFAULT_COVER_MS, 80, 800)
        speedSlider("Inner speed", "innerMs", DEFAULT_INNER_MS, 60, 2500)
        // ---- finish pass: when the fill is done the effect runs once more, then the screen is solid ----
        val finRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), 0, 0) }
        finRow.addView(TextView(this).apply { text = "Finish"; setTextColor(Color.WHITE); textSize = 13f; setPadding(0, 0, dp(10), 0) })
        val finChips = ArrayList<TextView>()
        fun markFin(m: Int) = finChips.forEachIndexed { k, c ->
            c.background = if (k == m) round(PURPLE, 20) else round(Color.argb(170, 42, 38, 64), 20)
        }
        val finLbl = TextView(this).apply { setTextColor(MUTED); textSize = 12f; setPadding(dp(6), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val finHelp = listOf("the fill ends, done", "flows back the other way, then solid", "a second pass the same way, then solid", "rings out from the middle, then solid")
        listOf("Off", "A", "B", "C").forEachIndexed { k, label ->
            val c = chip(label) {
                FxSettings.put(sp, curFx(), "finMode", k)
                mark(curFx()); previewFinish(); markFin(k); finLbl.text = finHelp[k]; restartService()
                preview.slowMo = false; preview.playStart = android.os.SystemClock.uptimeMillis()
            }
            finChips.add(c); finRow.addView(c, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        val fm0 = FxSettings.get(sp, curFx(), "finMode"); markFin(fm0); finLbl.text = finHelp[fm0]
        fxRefresh.add { val m = FxSettings.get(sp, curFx(), "finMode"); markFin(m); finLbl.text = finHelp[m] }
        finRow.addView(finLbl, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sc.addView(finRow)
        // finish settings under the buttons: its speed, and how blurry the picture stays until it runs
        val cols2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        sc.addView(cols2)
        speedSlider("Speed", "finMs", DEFAULT_FIN_MS, 300, 2500, into = cols2)
        speedSlider("Blur", "softBlur", (DEFAULT_SOFT_BLUR * 10).toInt(), 0, 30, into = cols2, step = 1,
            fmt = { if (it == 0) "off" else "%.1f".format(it / 10f) })
        speedSlider("Wobble", "softWobble", 0, 0, 10, into = cols2, step = 1, fmt = { if (it == 0) "off" else "$it" })
        trainLbl = TextView(this).apply { setTextColor(Color.rgb(170, 220, 170)); textSize = 13f; visibility = View.GONE }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        // training: learn this user's own opening speed (HelperService.trainStep)
        chips.addView(chip("Learn my opening") {
            trainLbl.visibility = View.VISIBLE
            val hp = FoldService.helper
            if (hp == null) { trainLbl.text = "Needs the Shizuku helper connected first (turn the effect on)"; return@chip }
            hp.startTraining(TRAIN_OPENS)
            trainCancel.visibility = View.VISIBLE
            pollTraining()
        }, gap())
        trainCancel = chip("Cancel") {
            try { FoldService.helper?.startTraining(0) } catch (_: Throwable) {}
            ui.removeCallbacks(trainPoll); trainCancel.visibility = View.GONE
            trainLbl.text = "Training cancelled, nothing changed"
        }.apply { visibility = View.GONE }
        fun slideText(on: Boolean) = if (on) "Picture slides  ✓" else "Picture slides  ✕"
        chips.addView(chip(slideText(sp.getBoolean("slide", true))) { c ->
            val on = !sp.getBoolean("slide", true)
            sp.edit().putBoolean("slide", on).apply(); preview.slide = on; c.text = slideText(on); restartService()
        }, gap())
        // back to the shared default for the selected effect only
        chips.addView(chip("Reset effect") {
            FxSettings.reset(sp, curFx()); mark(curFx()); fxRefresh.forEach { it() }; previewFinish(); restartService()
        }, gap())
        sc.addView(chips)
        sc.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0)
            addView(trainLbl, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(trainCancel)
        })
        // the result of a finished training, shown once after the screen rebuilt with the new speed
        sp.getString("trainMsg", null)?.let { trainLbl.text = it; trainLbl.visibility = View.VISIBLE; sp.edit().remove("trainMsg").apply() }
        // ---- home-screen pictures: the effect draws what the screen will show, so keep them current ----
        val capRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        capLbl = TextView(this).apply { setTextColor(MUTED); textSize = 12f; setPadding(dp(8), 0, 0, 0); maxLines = 2 }
        capRow.addView(chip("Capture home screen") {
            val hp = FoldService.helper
            if (hp == null) { capLbl.text = "Needs the helper connected (turn the effect on)"; return@chip }
            // tell them first (11-09): the jump to the home screen looks like the app closed. Shown here,
            // not as a toast: a toast over the home screen would end up in the picture
            capLbl.text = "Hold on a few seconds, don't close the phone, capturing your home screen"
            capLbl.setTextColor(Color.WHITE)
            Thread {
                Thread.sleep(1500)
                // show the home screen, capture it, come back
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Thread.sleep(1300)
                val r = try { hp.captureNow() } catch (e: Throwable) { "failed" }
                capResult = when (r) { "inner", "cover" -> "Captured the $r screen ✓"; else -> "Not captured: $r" }
                // come back through the helper: from here (behind the home screen) Android blocks our own startActivity
                val back = try { hp.openApp() } catch (e: Throwable) { false }
                if (!back) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.start()
        })
        capRow.addView(capLbl, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sc.addView(capRow)
        showCaptures()
        val top = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) }
        if (narrow) {
            // cover screen (narrow): one scrolling column, preview at a fixed height
            root.orientation = LinearLayout.VERTICAL
            side.setPadding(0, 0, 0, 0)
            side.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, dp(300)).apply { topMargin = dp(14) })
            side.addView(effects); side.addView(sc, top)
            root.addView(ScrollView(this).apply { addView(side); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            side.addView(effects)
            root.addView(ScrollView(this).apply { addView(side); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(dp(270), MATCH_PARENT))
            right.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            right.addView(sc, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        val pad = dp(20)
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(pad + b.left, pad / 2 + b.top, pad + b.right, pad / 2 + b.bottom); ins
        }
        // first open: one simple setup screen on top until Shizuku is allowed once
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setup = buildSetup()
        frame.addView(setup, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(frame)
    }

    // ---- first-run setup: one simple screen, one tap for permissions ----
    private lateinit var setup: View
    private var skipBtn: TextView? = null
    private val stepViews = ArrayList<Triple<TextView, TextView, TextView>>()   // circle, body, button
    private fun shizukuInstalled() = try { packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Throwable) { false }
    private fun shizukuUp() = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun shizukuAllowed() = shizukuUp() && try {
        rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }

    private fun buildSetup(): View {
        val page = FrameLayout(this).apply {
            isClickable = true
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0.5f, 0f)
                gradientRadius = dp(900).toFloat(); colors = intArrayOf(Color.rgb(58, 31, 110), Color.rgb(11, 9, 18))
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(28), dp(36), dp(28), dp(28)) }
        col.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(dp(3), Color.rgb(201, 168, 255)) }
            elevation = dp(16).toFloat(); outlineAmbientShadowColor = PURPLE; outlineSpotShadowColor = PURPLE
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(18) })
        col.addView(TextView(this).apply { text = "Set up Nightfold"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply { text = "Only once. About a minute."; textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(22)) })
        val steps = listOf(
            "Get Shizuku" to "A free helper app. It lets the effect follow your hinge smoothly and draw above everything.",
            "Start Shizuku" to "Open Shizuku and tap Start (its first time it walks you through Wireless debugging). Then come back here.",
            "Allow" to "One tap and the effect turns on.",
        )
        stepViews.clear()
        steps.forEachIndexed { i, (title, body) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = round(Color.argb(14, 255, 255, 255), 18, Color.argb(24, 255, 255, 255))
            }
            val circle = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
            row.addView(circle, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
            val bodyTv = TextView(this).apply { text = body; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(2), 0, 0) }
            texts.addView(bodyTv)
            val btn = TextView(this).apply {
                textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(22), dp(11), dp(22), dp(11))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(138, 92, 255), Color.rgb(107, 63, 216))).apply { cornerRadius = dp(24).toFloat() }
                elevation = dp(6).toFloat(); outlineSpotShadowColor = PURPLE
                text = when (i) { 0 -> "Get Shizuku"; 1 -> "Open Shizuku"; else -> "Allow" }
                setOnClickListener { setupAction(i) }
            }
            texts.addView(btn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            stepViews.add(Triple(circle, bodyTv, btn))
        }
        col.addView(TextView(this).apply {
            skipBtn = this
            text = "Not now"; textSize = 14f; setTextColor(MUTED); setPadding(dp(16), dp(14), dp(16), dp(8))
            setOnClickListener { setup.visibility = View.GONE }
        })
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        page.addView(scroll, FrameLayout.LayoutParams(dp(560).coerceAtMost(resources.displayMetrics.widthPixels), MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        page.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(android.view.WindowInsets.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }
        return page
    }

    private fun setupAction(i: Int) {
        when (i) {
            0 -> try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SHIZUKU_PKG"))) }
                 catch (_: Throwable) { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG"))) }
            1 -> packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { startActivity(it) }
            else -> turnOn()
        }
    }

    private fun refreshSetup() {
        if (!::setup.isInitialized) return
        val sp = getSharedPreferences("fx", Context.MODE_PRIVATE)
        val done = listOf(shizukuInstalled(), shizukuUp(), shizukuAllowed())
        if (done[2]) sp.edit().putBoolean("setupDone", true).apply()
        // debug: adb shell am start -n com.vibecodedstudio.nightfold/.MainActivity --ez setup true  -> show setup anyway
        if (sp.getBoolean("setupDone", false) && !intent.getBooleanExtra("setup", false)) { setup.visibility = View.GONE; return }
        val cur = done.indexOfFirst { !it }
        skipBtn?.text = if (cur < 0) "Continue" else "Not now"
        skipBtn?.setTextColor(if (cur < 0) Color.WHITE else MUTED)
        stepViews.forEachIndexed { i, (circle, body, btn) ->
            val ok = done[i]; val now = i == cur
            circle.text = if (ok) "✓" else "${i + 1}"
            circle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (ok) setColor(Color.rgb(60, 160, 110)) else if (now) setColor(PURPLE) else { setColor(Color.TRANSPARENT); setStroke(dp(2), Color.rgb(90, 84, 120)) }
            }
            body.visibility = if (now) View.VISIBLE else View.GONE
            btn.visibility = if (now) View.VISIBLE else View.GONE
            (circle.parent as View).alpha = if (now || ok) 1f else 0.5f
        }
    }

    private fun turnOn() {
        getSharedPreferences("fx", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        try {
            if (rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                rikka.shizuku.Shizuku.requestPermission(7); return       // permListener starts it once allowed
            }
        } catch (_: Throwable) {}
        restartService(force = true)
    }
    private fun turnOff() {
        getSharedPreferences("fx", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        stopService(Intent(this, FoldService::class.java)); refresh() }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var trainLbl: TextView
    private lateinit var trainCancel: TextView
    private val trainPoll = Runnable { pollTraining() }
    /** follows a training run in the helper; stops while the app is not on screen (onStop) and picks up again in
     *  onStart, since the run itself (closing + opening the phone) happens with the app in the background */
    private fun pollTraining() {
        ui.removeCallbacks(trainPoll)
        val r = try { FoldService.helper?.training() } catch (_: Throwable) { null }
        if (r == null || r == "idle" || r == "cancelled") { trainCancel.visibility = View.GONE; return }
        if (r.startsWith("done")) {
            val c = r.split(" ")[1].toInt()
            getSharedPreferences("fx", Context.MODE_PRIVATE).edit()
                .putInt("trained_coverMs", c).putInt("coverMs", c).remove("trained_innerMs")
                .putString("trainMsg", "Learned ✓  your opens take about $c ms, cover speed set to $c ms").apply()
            try { FoldService.helper?.startTraining(0) } catch (_: Throwable) {}   // back to idle so it is not applied twice
            restartService(); recreate()
            return
        }
        trainLbl.visibility = View.VISIBLE; trainCancel.visibility = View.VISIBLE
        trainLbl.text = "Training: close the phone, wake the cover screen, then open fully. ${r.replace("/", " of ")} done"
        ui.postDelayed(trainPoll, 400)
    }

    private lateinit var capLbl: TextView
    @Volatile private var capResult: String? = null
    /** when each screen's picture was last captured (automatic or the button) */
    private fun showCaptures() {
        if (!::capLbl.isInitialized) return
        val dir = getExternalFilesDir(null)
        fun t(n: String): String { val f = java.io.File(dir, n); return if (f.exists()) android.text.format.DateFormat.format("MMM d HH:mm", f.lastModified()).toString() else "none" }
        capLbl.setTextColor(MUTED)
        val head = capResult?.let { "$it  ·  " } ?: ""
        capLbl.text = head + "inner ${t("inner.png")} · cover ${t("cover.png")}"
    }

    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) preview.reloadPics()
        showCaptures()
        // the effect should be on but is not running (e.g. the restore broadcast after an update was killed because the
        // app was opened at the same moment): opening the app brings it back, same as RestoreReceiver
        if (getSharedPreferences("fx", MODE_PRIVATE).getBoolean("enabled", false) && !FoldService.running) restartService(force = true)
        refresh()
    }
    private val binderUp = rikka.shizuku.Shizuku.OnBinderReceivedListener { ui.post { refresh() } }
    private val binderDown = rikka.shizuku.Shizuku.OnBinderDeadListener { ui.post { refresh() } }

    private val permListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, r ->
        if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) restartService(force = true) else refresh()
    }
    override fun onStart() {
        super.onStart()
        FoldService.onRunningChanged = { refresh() }
        if (::trainLbl.isInitialized) pollTraining()        // a training run still going: show it again
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permListener)
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderUp); rikka.shizuku.Shizuku.addBinderDeadListener(binderDown)
        } catch (_: Throwable) {}
    }
    override fun onStop() {
        FoldService.onRunningChanged = null
        ui.removeCallbacks(trainPoll)
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permListener)
            rikka.shizuku.Shizuku.removeBinderReceivedListener(binderUp); rikka.shizuku.Shizuku.removeBinderDeadListener(binderDown)
        } catch (_: Throwable) {}
        super.onStop()
    }

    private fun previewFinish() {
        val sp = getSharedPreferences("fx", MODE_PRIVATE)
        val f = savedFx(sp)
        fun g(k: String) = FxSettings.get(sp, f, k)
        preview.finish(g("finMode"), g("finMs"), g("softBlur") / 10f, g("softWobble").toFloat())
        preview.timing(g("coverMs"), g("innerMs"))
    }

    private fun restartService(force: Boolean = false) {
        if (force || isRunning) startForegroundService(Intent(this, FoldService::class.java))
        refresh()
    }

    private val isRunning get() = FoldService.running

    private fun refresh() {
        refreshSetup()
        settingSwitch = true; onSwitch.isChecked = isRunning; settingSwitch = false
        val up = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Throwable) { false }
        val allowed = up && try { rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
        val need = when {
            !up -> "Shizuku isn't running: start it in the Shizuku app, the effect connects by itself"
            !allowed -> "Turn the switch on and tap Allow (the only permission)"
            else -> null
        }
        val helper = FoldService.helper?.let { try { it.status() } catch (e: Throwable) { "dead" } } ?: FoldService.helperState
        status.text = (if (isRunning) "● Running" else "○ Off") + (if (need != null) "\n$need" else "") +
            "\nHelper: $helper"
        status.setTextColor(if (need != null) Color.rgb(255, 160, 120) else if (isRunning) Color.rgb(127, 224, 160) else MUTED)
    }
}
