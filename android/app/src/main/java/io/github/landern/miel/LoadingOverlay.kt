package io.github.landern.miel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Covers the lower screen with the game's art while it copies, boots or checkpoints. */
class LoadingOverlay(context: Context, title: String?) : FrameLayout(context) {
    private val art = ImageView(context)
    private val status = TextView(context)
    private val bar = Bar(context)
    private var shown = true

    init {
        val dp = resources.displayMetrics.density
        setBackgroundColor(0xff303638.toInt())
        isClickable = true
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(art, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val scrim = View(context)
        scrim.background =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x80303638.toInt(), 0xd9303638.toInt(), 0xfa303638.toInt()),
            )
        addView(scrim, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER_HORIZONTAL
        val padding = (32 * dp).roundToInt()
        column.setPadding(padding, padding, padding, (48 * dp).roundToInt())
        val heading = TextView(context)
        heading.setTextColor(0xfffffef8.toInt())
        heading.textSize = 24f
        heading.typeface = resources.getFont(R.font.nunito_bold)
        heading.gravity = Gravity.CENTER
        heading.maxLines = 2
        heading.ellipsize = TextUtils.TruncateAt.END
        heading.text = title
        heading.visibility = if (title.isNullOrEmpty()) GONE else VISIBLE
        column.addView(heading)
        status.setTextColor(0xccfffef8.toInt())
        status.textSize = 14f
        status.typeface = resources.getFont(R.font.nunito_regular)
        status.gravity = Gravity.CENTER
        status.setLineSpacing(0f, 1.2f)
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        val statusArea = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        statusArea.topMargin = (8 * dp).roundToInt()
        column.addView(status, statusArea)
        val barArea = LinearLayout.LayoutParams((220 * dp).roundToInt(), (6 * dp).roundToInt())
        barArea.topMargin = (20 * dp).roundToInt()
        column.addView(bar, barArea)
        val columnArea = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        columnArea.gravity = Gravity.BOTTOM
        addView(column, columnArea)
    }

    fun art(bitmap: Bitmap) {
        art.setImageBitmap(bitmap)
        art.alpha = 0f
        art.animate()
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /**
     * Shows `text` with a progress fraction, PULSE or NONE. An empty text fades the overlay out.
     */
    fun update(text: String, progress: Float) {
        if (text.isNotEmpty() && status.text.toString() != text) status.text = text
        bar.visibility = if (progress.isNaN()) GONE else VISIBLE
        bar.progress = if (progress >= 0) progress.coerceAtMost(1f) else PULSE
        bar.invalidate()
        val show = text.isNotEmpty()
        if (show == shown) return
        shown = show
        isClickable = show
        animate().cancel()
        if (show) {
            visibility = VISIBLE
            animate()
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } else {
            animate().alpha(0f).setDuration(160).withEndAction { visibility = GONE }.start()
        }
    }

    /** A slim honey bar; a sliding segment while the progress is unknown. */
    private class Bar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var progress = PULSE

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Loading progress"
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.ProgressBar"
            if (progress >= 0) {
                info.rangeInfo =
                    AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                        0f,
                        1f,
                        progress,
                    )
            }
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()
            val radius = height / 2
            paint.shader = null
            paint.color = 0x33fffef8
            rect.set(0f, 0f, width, height)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader =
                LinearGradient(
                    0f,
                    0f,
                    width,
                    0f,
                    0xffe9b949.toInt(),
                    0xffd9a936.toInt(),
                    Shader.TileMode.CLAMP,
                )
            if (progress >= 0) {
                rect.set(0f, 0f, maxOf(height, width * minOf(1f, progress)), height)
            } else {
                val moving = ValueAnimator.areAnimatorsEnabled()
                val t =
                    if (moving) (SystemClock.uptimeMillis() % PERIOD) / PERIOD.toFloat() else .5f
                val eased = (.5 - .5 * cos(PI * t)).toFloat()
                val segment = width * .32f
                val x = -segment + (width + segment) * eased
                rect.set(maxOf(0f, x), 0f, minOf(width, x + segment), height)
                if (moving) postInvalidateOnAnimation()
            }
            if (rect.width() > 0) canvas.drawRoundRect(rect, radius, radius, paint)
        }

        private companion object {
            const val PERIOD = 1600L
        }
    }

    companion object {
        /** Progress for a bar without a known fraction. */
        const val PULSE = -1f

        /** Progress that hides the bar. */
        const val NONE = Float.NaN
    }
}
