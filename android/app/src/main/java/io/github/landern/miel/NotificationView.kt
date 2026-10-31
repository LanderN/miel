package io.github.landern.miel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator

/** A passive card at the top of the lower display; game details keep their original position. */
class NotificationView(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val regular = resources.getFont(R.font.nunito_regular)
    private val bold = resources.getFont(R.font.nunito_bold)
    private val card =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xfffffef8.toInt(), 0xfff6f3eb.toInt(), 0xffede8dc.toInt()),
        )
    private var value: MielNotification? = null
    private val listener: (MielNotification?) -> Unit = { next ->
        animate().cancel()
        if (next == null) {
            animate()
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    value = null
                    contentDescription = null
                    invalidate()
                }
                .start()
        } else {
            value = next
            contentDescription = "miel. ${next.title}. ${next.detail}. ${next.count}"
            animate().alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            invalidate()
        }
    }

    init {
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MielNotifications.observe(listener)
    }

    override fun onDetachedFromWindow() {
        MielNotifications.remove(listener)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val item = value ?: return
        val s = minOf(width / 480f, height / 270f)
        val left = 12 * s
        val top = 12 * s
        val right = width - left
        val bottom = top + 80 * s
        paint.color = 0xfff6f3eb.toInt()
        rect.set(left, top, right, bottom)
        paint.setShadowLayer(4 * s, s, 2 * s, 0x38303638)
        canvas.drawRoundRect(rect, 18 * s, 18 * s, paint)
        paint.clearShadowLayer()
        card.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        card.cornerRadius = 18 * s
        card.setStroke(s.toInt().coerceAtLeast(1), 0xffd9d4c8.toInt())
        card.draw(canvas)
        paint.color = 0xffe9b949.toInt()
        canvas.drawCircle(left + 25 * s, top + 25 * s, 13 * s, paint)
        fun text(
            text: String,
            x: Float,
            y: Float,
            size: Float,
            color: Int,
            max: Float,
            bold: Boolean = false,
        ) {
            paint.color = color
            paint.textSize = size * s
            paint.typeface = if (bold) this@NotificationView.bold else regular
            canvas.drawText(
                TextUtils.ellipsize(text, paint, max.coerceAtLeast(0f), TextUtils.TruncateAt.END)
                    .toString(),
                x,
                y,
                paint,
            )
        }
        text(
            if (item.warning) "!" else if (item.finished) "✓" else "↓",
            left + 20 * s,
            top + 31 * s,
            18f,
            0xff303638.toInt(),
            20 * s,
        )
        val x = left + 48 * s
        val end = right - 14 * s
        text("miel · ${item.heading}", x, top + 19 * s, 10f, 0xff69706f.toInt(), end - x - 90 * s)
        paint.textSize = 10 * s
        val countWidth = paint.measureText(item.count)
        text(item.count, end - countWidth, top + 19 * s, 10f, 0xff69706f.toInt(), countWidth + s)
        text(item.title, x, top + 38 * s, 14f, 0xff303638.toInt(), end - x, true)
        text(item.detail, x, top + 55 * s, 11f, 0xff69706f.toInt(), end - x)
        val barLeft = left + 14 * s
        val barRight = right - 14 * s
        rect.set(barLeft, bottom - 13 * s, barRight, bottom - 8 * s)
        paint.color = 0xffd9d4c8.toInt()
        canvas.drawRoundRect(rect, 3 * s, 3 * s, paint)
        paint.color = 0xffe9b949.toInt()
        if (item.progress < 0) {
            val w = barRight - barLeft
            if (ValueAnimator.areAnimatorsEnabled()) {
                val t = (SystemClock.uptimeMillis() % 1600) / 1600f
                rect.left = barLeft + (w * 1.3f * t - w * .3f).coerceAtLeast(0f)
                rect.right = (barLeft + w * 1.3f * t).coerceAtMost(barRight)
                if (alpha > 0f && isShown) postInvalidateOnAnimation()
            } else {
                rect.right = barLeft + w * .3f
            }
        } else rect.right = barLeft + (barRight - barLeft) * item.progress.coerceIn(0f, 1f)
        if (rect.width() > 0) canvas.drawRoundRect(rect, 3 * s, 3 * s, paint)
        if (item.finished && item.unavailableFraction > 0) {
            rect.left = barRight - (barRight - barLeft) * item.unavailableFraction.coerceIn(0f, 1f)
            rect.right = barRight
            paint.color = 0xffd9d4c8.toInt()
            canvas.drawRoundRect(rect, 3 * s, 3 * s, paint)
        }
    }
}
