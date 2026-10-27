package io.github.landern.miel

import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** PlayStation controller and disc controls on the lower screen. */
class PlayControls(
    context: Context,
    private val opened: () -> Unit,
    private val dismissed: () -> Unit,
    private val insert: (Int) -> Unit,
    private val toggleAnalog: () -> Unit,
) : FrameLayout(context) {
    private val cream = 0xfff5efe4.toInt()
    private val surface = 0xfffffaf1.toInt()
    private val ink = 0xff4b3928.toInt()
    private val muted = 0xff6c5b46.toInt()
    private val honey = 0xffefb13a.toInt()
    private val ring = 0xffa8731c.toInt()
    private val line = 0xffded2bd.toInt()
    private val regular = resources.getFont(R.font.nunito_regular)
    private val bold = resources.getFont(R.font.nunito_bold)
    private val dock = LinearLayout(context)
    private val current = label("", 14f)
    private val change = action("R3", "Change disc", true) { open() }
    private var updatingAnalog = false
    private val analog =
        Switch(context).apply {
            showText = false
            thumbDrawable = background(surface, 14, line).apply { setSize(dp(28), dp(28)) }
            trackDrawable = background(honey, 14).apply { setSize(dp(48), dp(28)) }
            switchMinWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "Analog controls"
            thumbTintList = null
            trackTintList =
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(honey, line),
                )
            setOnCheckedChangeListener { _, _ -> if (!updatingAnalog) toggleAnalog() }
        }
    private val discRow = LinearLayout(context)
    private val divider = View(context)
    private val analogRow = LinearLayout(context)
    private val panel = LinearLayout(context)
    private val rows = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val choices = ArrayList<TextView>()
    private var count = 0
    private var index = 0
    private var selected = 0
    var isOpen = false
        private set

    init {
        visibility = GONE
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
        divider.setBackgroundColor(line)
        dock.addView(
            divider,
            LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            },
        )
        analogRow.gravity = Gravity.CENTER_VERTICAL
        analogRow.setOnClickListener { analog.toggle() }
        analogRow.addView(
            label("Analog controls", 16f, true),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        analogRow.addView(analog)
        dock.addView(analogRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(
            dock,
            LayoutParams(dp(340), WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(24)
            },
        )

        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(24), dp(24), dp(24), dp(24))
        panel.setBackgroundColor(cream)
        panel.visibility = GONE
        panel.isClickable = true
        panel.addView(label("Change disc", 28f, true))
        rows.orientation = LinearLayout.VERTICAL
        scroll.isFillViewport = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(16), 0, dp(16))
        scroll.addView(rows)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(context)
        footer.gravity = Gravity.CENTER_VERTICAL
        footer.addView(action("B", "Cancel", false) { close() })
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(action("A", "Insert disc", true) { confirm() })
        panel.addView(footer)
        addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun update(
        count: Int,
        index: Int,
        ready: Boolean,
        failed: Boolean,
        showAnalog: Boolean,
        analogEnabled: Boolean,
    ) {
        this.count = count
        this.index = index
        visibility = if (ready && (count > 1 || showAnalog)) VISIBLE else GONE
        discRow.visibility = if (count > 1) VISIBLE else GONE
        divider.visibility = if (count > 1 && showAnalog) VISIBLE else GONE
        analogRow.visibility = if (showAnalog) VISIBLE else GONE
        updatingAnalog = true
        analog.isChecked = analogEnabled
        updatingAnalog = false
        current.text = if (failed) "Couldn’t change disc · Retry" else "Disc ${index + 1} of $count"
    }

    fun open() {
        if (count < 2 || isOpen) return
        opened()
        isOpen = true
        selected = index.coerceIn(0, count - 1)
        dock.visibility = GONE
        panel.visibility = VISIBLE
        rows.removeAllViews()
        choices.clear()
        repeat(count) { disc ->
            val row =
                label("Disc ${disc + 1}" + if (disc == index) "   ·   Inserted" else "", 18f, true)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(20), dp(16), dp(20), dp(16))
            row.isClickable = true
            relief(row)
            row.setOnClickListener {
                selected = disc
                confirm()
            }
            rows.addView(
                row,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                },
            )
            choices.add(row)
        }
        highlight()
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
                    selected = (selected - 1 + count) % count
                    highlight()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    selected = (selected + 1) % count
                    highlight()
                }
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
        insert(selected)
        close()
    }

    private fun highlight() {
        choices.forEachIndexed { disc, view ->
            view.background =
                background(
                    if (disc == selected) 0xfff3dfb3.toInt() else surface,
                    18,
                    if (disc == selected) ring else line,
                )
            view.isSelected = disc == selected
        }
        choices.getOrNull(selected)?.let { row ->
            scroll.post { scroll.smoothScrollTo(0, row.top) }
        }
    }

    private fun action(key: String, text: String, primary: Boolean, clicked: () -> Unit): View =
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
