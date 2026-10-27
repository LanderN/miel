package io.github.landern.miel

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The lower screen during single-screen play: the game's art, title and play time, the time and
 * battery, and fast-forward, Library, PlayStation controller and disc controls. The disc chooser
 * and fast-forward settings open as a sheet over it.
 */
class PlayControls(
    context: Context,
    title: String?,
    private val opened: () -> Unit,
    private val dismissed: () -> Unit,
    private val insert: (Int) -> Unit,
    private val toggleAnalog: () -> Unit,
    private val toggleFastForward: () -> Unit,
    fastForwardSettings: Map<String, String>,
    private val changeFastForward: (key: String, value: String) -> Unit,
    private val leave: () -> Unit,
    resume: () -> Unit,
) : FrameLayout(context) {
    private val cream = 0xfff5efe4.toInt()
    private val surface = 0xfffffaf1.toInt()
    private val ink = 0xff4b3928.toInt()
    private val muted = 0xff6c5b46.toInt()
    private val honey = 0xffefb13a.toInt()
    private val ring = 0xffa8731c.toInt()
    private val line = 0xffded2bd.toInt()
    private val paper = 0xfffbf7ef.toInt()
    private val paperMuted = 0xccfbf7ef.toInt()
    private val regular = resources.getFont(R.font.nunito_regular)
    private val bold = resources.getFont(R.font.nunito_bold)
    private val dock = LinearLayout(context)
    private val current = label("", 14f)
    private val change = action("R3", "Change disc", true) { open() }
    private var updating = false
    private val analog = switch("Analog controls", toggleAnalog)
    private val fastForward = switch("Fast-forward", toggleFastForward)
    private val art = ImageView(context)
    private val played = label("", 14f)
    private val battery = label("", 14f)
    private var batteryAt = 0L
    private val resumeButton = action(null, "Resume", true) { resume() }
    private val discRow = LinearLayout(context)
    private val divider = View(context)
    private val analogRow = LinearLayout(context)
    private val analogDivider = View(context)
    private val fastForwardRow = LinearLayout(context)
    private var settings = fastForwardSettings
    private val settingsSummary = label("", 14f)
    private val settingValues = ArrayList<TextView>()
    private var choosingDisc = true
    private val panelTitle = label("", 28f, true)
    private val discFooter = LinearLayout(context)
    private val settingsFooter = LinearLayout(context)
    private val panel = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val choices = ArrayList<View>()
    private var count = 0
    private var index = 0
    private var selected = 0
    var isOpen = false
        private set

    init {
        visibility = GONE
        isClickable = true
        setBackgroundColor(0xff3f3024.toInt())
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(art, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(
            View(context).apply {
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xb33f3024.toInt(), 0xd93f3024.toInt(), 0xfa3f3024.toInt()),
                    )
            },
            LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        val bar = LinearLayout(context)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(24), dp(16), dp(16), 0)
        val clock =
            TextClock(context).apply {
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
                textSize = 16f
                typeface = bold
                setTextColor(paper)
            }
        bar.addView(clock)
        battery.setTextColor(paperMuted)
        bar.addView(
            battery,
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        bar.addView(action(null, "Library", false) { leave() })
        bar.addView(
            resumeButton,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) },
        )
        addView(bar, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.clipChildren = false
        val heading =
            label(title.orEmpty(), 24f, true).apply {
                setTextColor(paper)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
            }
        column.addView(heading, LinearLayout.LayoutParams(dp(420), WRAP_CONTENT))
        played.setTextColor(0xfff5bc45.toInt())
        column.addView(
            played,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(16)
            },
        )
        dock.orientation = LinearLayout.VERTICAL
        dock.setPadding(dp(16), dp(8), dp(16), dp(8))
        dock.background = background(surface, 26, line)
        dock.elevation = dp(3).toFloat()
        dock.outlineAmbientShadowColor = 0x244b3928
        dock.outlineSpotShadowColor = 0x384b3928
        dock.clipChildren = false
        clipChildren = false
        discRow.gravity = Gravity.CENTER_VERTICAL
        discRow.addView(current, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        discRow.addView(change)
        dock.addView(discRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addDivider(divider)
        switchRow(analogRow, "Analog controls", analog)
        addDivider(analogDivider)
        switchRow(fastForwardRow, "Fast-forward", fastForward)
        addDivider(View(context))
        val settingsRow = LinearLayout(context)
        settingsRow.gravity = Gravity.CENTER_VERTICAL
        settingsRow.minimumHeight = dp(48)
        settingsRow.setOnClickListener { openSettings() }
        settingsRow.addView(
            label("Fast-forward settings", 16f, true),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        settingsRow.addView(settingsSummary)
        dock.addView(settingsRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        summarize()
        column.addView(dock, LinearLayout.LayoutParams(dp(340), WRAP_CONTENT))
        addView(
            column,
            LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(24) },
        )

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(24), dp(24), dp(24), dp(24))
        panel.setBackgroundColor(cream)
        panel.visibility = GONE
        panel.isClickable = true
        panel.addView(panelTitle)
        rows.orientation = LinearLayout.VERTICAL
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(16), 0, dp(16))
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        discFooter.gravity = Gravity.CENTER_VERTICAL
        discFooter.addView(action("B", "Cancel", false) { close() })
        discFooter.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        discFooter.addView(action("A", "Insert disc", true) { confirm() })
        panel.addView(discFooter)
        settingsFooter.gravity = Gravity.CENTER_VERTICAL
        settingsFooter.addView(action("B", "Done", false) { close() })
        settingsFooter.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        settingsFooter.addView(action("A", "Change", true) { confirm() })
        panel.addView(settingsFooter)
        addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun art(bitmap: Bitmap) = art.setImageBitmap(bitmap)

    /**
     * `played` is the game's total play time in milliseconds. A `popup` covers a dual-screen game's
     * lower screen and offers Resume.
     */
    fun update(
        count: Int,
        index: Int,
        ready: Boolean,
        popup: Boolean,
        failed: Boolean,
        showAnalog: Boolean,
        analogEnabled: Boolean,
        fastForwarding: Boolean,
        played: Long,
    ) {
        this.count = count
        this.index = index
        visibility = if (ready) VISIBLE else GONE
        resumeButton.visibility = if (popup) VISIBLE else GONE
        discRow.visibility = if (count > 1) VISIBLE else GONE
        divider.visibility = if (count > 1 && showAnalog) VISIBLE else GONE
        analogRow.visibility = if (showAnalog) VISIBLE else GONE
        analogDivider.visibility = if (count > 1 || showAnalog) VISIBLE else GONE
        updating = true
        analog.isChecked = analogEnabled
        fastForward.isChecked = fastForwarding
        updating = false
        current.text = if (failed) "Couldn’t change disc · Retry" else "Disc ${index + 1} of $count"
        val playedText = "${PlayTime.duration(played)} played"
        if (this.played.text.toString() != playedText) this.played.text = playedText
        val now = SystemClock.uptimeMillis()
        if (batteryAt == 0L || now - batteryAt > 30_000) {
            batteryAt = now
            val level =
                context
                    .getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            battery.text = if (level in 0..100) "$level%" else ""
        }
    }

    /** Opens the disc chooser. */
    fun open() {
        if (count < 2 || isOpen) return
        sheet(true, "Change disc")
        selected = index.coerceIn(0, count - 1)
        repeat(count) { disc ->
            choice(
                label("Disc ${disc + 1}" + if (disc == index) "   ·   Inserted" else "", 18f, true),
            ) {
                selected = disc
                confirm()
            }
        }
        highlight()
    }

    private fun openSettings() {
        if (isOpen) return
        sheet(false, "Fast-forward")
        selected = 0
        FAST_FORWARD_OPTIONS.forEachIndexed { option, it ->
            val value = label("", 18f)
            settingValues.add(value)
            val row = LinearLayout(context)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(label(it.label, 18f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(value)
            choice(row) {
                selected = option
                confirm()
            }
        }
        summarize()
        highlight()
    }

    private fun sheet(discs: Boolean, title: String) {
        opened()
        isOpen = true
        choosingDisc = discs
        panelTitle.text = title
        discFooter.visibility = if (discs) VISIBLE else GONE
        settingsFooter.visibility = if (discs) GONE else VISIBLE
        dock.visibility = GONE
        panel.visibility = VISIBLE
        rows.removeAllViews()
        choices.clear()
        settingValues.clear()
    }

    private fun choice(row: View, clicked: () -> Unit) {
        row.setPadding(dp(20), dp(16), dp(20), dp(16))
        row.isClickable = true
        relief(row)
        row.setOnClickListener { clicked() }
        rows.addView(
            row,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) },
        )
        choices.add(row)
    }

    /** Moves the selected fast-forward setting `step` values along. */
    private fun cycle(step: Int) {
        val option = FAST_FORWARD_OPTIONS[selected]
        val values = option.values
        val value =
            values[Math.floorMod(values.indexOf(settings.getValue(option.key)) + step, values.size)]
        settings = settings + (option.key to value)
        changeFastForward(option.key, value)
        summarize()
    }

    private fun summarize() {
        FAST_FORWARD_OPTIONS.forEachIndexed { option, it ->
            settingValues.getOrNull(option)?.text = it.label(settings.getValue(it.key))
        }
        val speed = FAST_FORWARD_OPTIONS[2].label(settings.getValue("speed"))
        settingsSummary.text =
            if (settings["button"] == "off") speed
            else "${FAST_FORWARD_OPTIONS[0].label(settings.getValue("button"))} · $speed"
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        panel.visibility = GONE
        dock.visibility = VISIBLE
        dismissed()
    }

    fun key(event: KeyEvent): Boolean {
        if (!isOpen) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    selected = (selected - 1 + choices.size) % choices.size
                    highlight()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    selected = (selected + 1) % choices.size
                    highlight()
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!choosingDisc) cycle(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!choosingDisc) cycle(1)
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) confirm()
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_BUTTON_THUMBR -> if (event.repeatCount == 0) close()
            }
        }
        return true
    }

    private fun confirm() {
        if (!choosingDisc) return cycle(1)
        insert(selected)
        close()
    }

    private fun highlight() {
        choices.forEachIndexed { choice, view ->
            view.background =
                background(
                    if (choice == selected) 0xfff3dfb3.toInt() else surface,
                    18,
                    if (choice == selected) ring else line,
                )
            view.isSelected = choice == selected
        }
        choices.getOrNull(selected)?.let { row ->
            scroll.post { scroll.smoothScrollTo(0, row.top) }
        }
    }

    private fun switch(description: String, toggled: () -> Unit) =
        Switch(context).apply {
            showText = false
            thumbDrawable = background(surface, 14, line).apply { setSize(dp(28), dp(28)) }
            trackDrawable = background(honey, 14).apply { setSize(dp(48), dp(28)) }
            switchMinWidth = dp(48)
            minHeight = dp(48)
            contentDescription = description
            thumbTintList = null
            trackTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(honey, line),
                )
            setOnCheckedChangeListener { _, _ -> if (!updating) toggled() }
        }

    private fun switchRow(row: LinearLayout, text: String, switch: Switch) {
        row.gravity = Gravity.CENTER_VERTICAL
        row.setOnClickListener { switch.toggle() }
        row.addView(label(text, 16f, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(switch)
        dock.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun addDivider(view: View) {
        view.setBackgroundColor(line)
        dock.addView(
            view,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            },
        )
    }

    /** A pill with a controller badge for `key`, or without one when it is null. */
    private fun action(key: String?, text: String, primary: Boolean, clicked: () -> Unit): View =
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background =
                background(
                    if (primary) honey else surface,
                    26,
                )
            isClickable = true
            isFocusable = true
            relief(this)
            contentDescription = text
            setOnClickListener { clicked() }
            if (key == null) {
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(label(text, 14f, true))
                return@apply
            }
            val badge =
                label(key, 12f, true).apply {
                    gravity = Gravity.CENTER
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(0)
                            setStroke(
                                dp(1),
                                if (primary) 0xff7e561c.toInt() else 0xff957a50.toInt(),
                            )
                        }
                }
            addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            addView(label(text, 14f, true))
        }

    private fun label(value: String, size: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (bold) ink else muted)
            typeface = if (bold) this@PlayControls.bold else regular
        }

    private fun background(color: Int, radius: Int, border: Int? = null) =
        GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                when (color) {
                    cream -> intArrayOf(0xfff8f2e8.toInt(), cream, 0xffeee5d6.toInt())
                    surface -> intArrayOf(surface, 0xfffcf6eb.toInt(), 0xfff8efdf.toInt())
                    honey -> intArrayOf(0xfff3bb48.toInt(), honey, 0xffe8a72f.toInt())
                    else -> intArrayOf(color, color)
                },
            )
            .apply {
                cornerRadius = dp(radius).toFloat()
                if (border != null) setStroke(dp(if (border == ring) 2 else 1), border)
            }

    private fun relief(view: View) {
        view.outlineAmbientShadowColor = 0x244b3928
        view.outlineSpotShadowColor = 0x384b3928
        view.stateListAnimator =
            StateListAnimator().apply {
                fun state(attributes: IntArray, height: Int) {
                    addState(
                        attributes,
                        ObjectAnimator.ofFloat(view, "elevation", dp(height).toFloat()).apply {
                            duration = 160
                            interpolator = DecelerateInterpolator()
                        },
                    )
                }
                state(intArrayOf(android.R.attr.state_pressed), 0)
                state(intArrayOf(android.R.attr.state_selected), 3)
                state(intArrayOf(android.R.attr.state_focused), 3)
                state(intArrayOf(), 2)
            }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
