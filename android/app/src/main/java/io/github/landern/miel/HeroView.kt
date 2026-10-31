package io.github.landern.miel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** What the artwork screen shows. */
data class Hero(
    val title: String,
    val detail: String,
    /** An image path, or an empty string. */
    val art: String,
    /** Draws `art` centred instead of covering the screen. */
    val icon: Boolean = false,
    val settings: SettingsDetail? = null,
    /** Play time and last played, below `detail`; see PlayTime.describe. */
    val played: String = "",
)

/**
 * The lower screen: screenshot details or setting context, drawn with Canvas. A new selection
 * crossfades in once its art is decoded off the UI thread.
 */
class HeroView(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val bounds = RectF()
    private val titleFace = context.resources.getFont(R.font.nunito_extrabold)
    private val detailFace = context.resources.getFont(R.font.nunito_regular)
    private val cache = LruCache<String, Bitmap>(4)
    private val fade = ValueAnimator.ofFloat(0f, 1f).setDuration(220)
    private var decoder: ExecutorService? = null
    private var from = EMPTY
    private var to = EMPTY
    private var wanted = EMPTY
    private var fromArt: Bitmap? = null
    private var toArt: Bitmap? = null
    @Volatile private var wantedPath = ""

    /** Crossfade progress from `from` to `to`. */
    private var progress = 1f

    init {
        fade.interpolator = DecelerateInterpolator(1.5f)
        fade.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    fun update(hero: Hero) {
        wanted = hero
        val path = hero.art
        wantedPath = path
        val hit = if (path.isEmpty()) null else cache.get(path)
        if (path.isEmpty() || hit != null) {
            show(hero, hit)
            return
        }
        val executor = decoder ?: Executors.newSingleThreadExecutor().also { decoder = it }
        val width = if (getWidth() > 0) getWidth() else resources.displayMetrics.widthPixels
        val height = if (getHeight() > 0) getHeight() else resources.displayMetrics.heightPixels
        executor.execute {
            if (path != wantedPath) return@execute
            val art = cache.get(path) ?: decode(path, width, height)
            if (art != null) cache.put(path, art)
            post { if (decoder != null && path == wantedPath) show(wanted, art) }
        }
    }

    /**
     * Retargets the crossfade. A transition interrupted past its midpoint keeps its incoming frame
     * as the new outgoing one.
     */
    private fun show(next: Hero, art: Bitmap?) {
        if (art !== toArt || next != to) {
            if (progress >= .5f) {
                from = to
                fromArt = toArt
            }
            fade.cancel()
            progress = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
            if (progress == 0f) fade.start()
        }
        to = next
        toArt = art
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        shade.shader =
            LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(0x00222729, 0x55222729, 0xee222729.toInt()),
                floatArrayOf(.32f, .58f, 1f),
                Shader.TileMode.CLAMP,
            )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fade.cancel()
        progress = 1f
        decoder?.shutdownNow()
        decoder = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        // The library's 24dp gutter, scaled down on small displays.
        val scale = minOf(resources.displayMetrics.density, width / 480f, height / 270f)
        canvas.drawColor(BACKGROUND)
        if (from.settings == null && to.settings == null) {
            val fromAlpha = if (toArt != null && !to.icon) 1f else 1 - progress
            drawArt(canvas, from, fromArt, fromAlpha, scale, width, height)
            drawArt(canvas, to, toArt, progress, scale, width, height)
            canvas.drawRect(0f, 0f, width, height, shade)
            drawText(canvas, from, 1 - minOf(1f, 2 * progress), scale, width, height)
            drawText(canvas, to, progress, scale, width, height)
            return
        }
        if (progress < 1f) drawScene(canvas, from, fromArt, 1f, scale, width, height)
        drawScene(canvas, to, toArt, progress, scale, width, height)
    }

    private fun drawScene(
        canvas: Canvas,
        hero: Hero,
        art: Bitmap?,
        alpha: Float,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        if (alpha <= 0f) return
        val layer = canvas.saveLayerAlpha(0f, 0f, width, height, (255 * alpha).toInt())
        val settings = hero.settings
        if (settings != null) {
            canvas.drawColor(0xfff6f3eb.toInt())
            drawSettings(canvas, settings, scale, width, height)
        } else {
            canvas.drawColor(BACKGROUND)
            drawArt(canvas, hero, art, 1f, scale, width, height)
            canvas.drawRect(0f, 0f, width, height, shade)
            drawText(canvas, hero, 1f, scale, width, height)
        }
        canvas.restoreToCount(layer)
    }

    private fun drawSettings(
        canvas: Canvas,
        detail: SettingsDetail,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        val gutter = 24 * scale
        val textWidth = (width - 2 * gutter).toInt().coerceAtLeast(1)
        var y = gutter
        fun text(value: String, size: Float, bold: Boolean = false, lines: Int = 1) {
            paint.typeface = if (bold) titleFace else detailFace
            paint.color = if (bold) 0xff303638.toInt() else 0xff69706f.toInt()
            paint.textSize = size * scale * resources.configuration.fontScale
            val layout =
                StaticLayout.Builder.obtain(value, 0, value.length, paint, textWidth)
                    .setIncludePad(false)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            canvas.save()
            canvas.translate(gutter, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
        }
        text(detail.context, 14f)
        y += 16 * scale
        text(detail.title, 28f, bold = true, lines = 2)
        detail.value?.let {
            y += 12 * scale
            text(it, 22f, bold = true)
        }
        y += 16 * scale
        text(detail.description, 16f, lines = 3)
        val preview = detail.preview
        val ruleTop = height - gutter - scale
        if (preview != null) {
            y += 16 * scale
            drawPreview(canvas, detail, scale, gutter, y, width - gutter, ruleTop - 16 * scale)
        }
        paint.color = 0xffd9d4c8.toInt()
        canvas.drawRect(gutter, height - gutter - scale, width - gutter, height - gutter, paint)
    }

    /** Two panels in a two-column grid between `top` and `bottom`; the current one is outlined. */
    private fun drawPreview(
        canvas: Canvas,
        detail: SettingsDetail,
        scale: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val gap = 16 * scale
        val column = (right - left - gap) / 2
        val labelSize = 14 * scale * resources.configuration.fontScale
        paint.textSize = labelSize
        val labelHeight = paint.fontMetrics.run { descent - ascent }
        val labelGap = 8 * scale
        val panelHeight = minOf(bottom - top - labelHeight - labelGap, column * .75f)
        if (panelHeight < 24 * scale) return
        val resolution = detail.preview == SettingsPreview.RESOLUTION
        val current = detail.previewValue
        val values = if (resolution) listOf("1", if (current == "1") "4" else current) else LAYOUTS
        val sideWidth = if (resolution) minOf(column, panelHeight) else column
        values.forEachIndexed { index, value ->
            val columnLeft = left + index * (column + gap)
            val panelLeft = columnLeft + (column - sideWidth) / 2
            bounds.set(panelLeft, top, panelLeft + sideWidth, top + panelHeight)
            val selected = value == current
            if (resolution) drawResolution(canvas, bounds, value.toIntOrNull() ?: 1, scale)
            else drawLayout(canvas, bounds, value.toIntOrNull() ?: 1, scale)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (selected) 3 else 2) * scale
            paint.color = if (selected) 0xffe9b949.toInt() else 0xffede8dc.toInt()
            val inset = paint.strokeWidth / 2
            canvas.drawRoundRect(
                bounds.left + inset,
                bounds.top + inset,
                bounds.right - inset,
                bounds.bottom - inset,
                14 * scale - inset,
                14 * scale - inset,
                paint,
            )
            paint.style = Paint.Style.FILL
            paint.typeface = if (selected) titleFace else detailFace
            paint.color = if (selected) 0xff303638.toInt() else 0xff69706f.toInt()
            paint.textSize = labelSize
            paint.textAlign = Paint.Align.CENTER
            val label = if (resolution) "$value×" else if (value == "1") "1 row" else "2 rows"
            canvas.drawText(
                label,
                bounds.centerX(),
                bounds.bottom + labelGap - paint.fontMetrics.ascent,
                paint,
            )
            paint.textAlign = Paint.Align.LEFT
        }
    }

    /** One row of 3:4 tiles, or two rows of squares, inside `panel`. */
    private fun drawLayout(canvas: Canvas, panel: RectF, rows: Int, scale: Float) {
        val pad = 12 * scale
        val gap = 6 * scale
        val count = 4
        val aspect = if (rows == 1) 4 / 3f else 1f
        val fitWidth = (panel.width() - 2 * pad - (count - 1) * gap) / count
        val fitHeight = (panel.height() - 2 * pad - (rows - 1) * gap) / rows / aspect
        val tileWidth = minOf(fitWidth, fitHeight)
        val tileHeight = tileWidth * aspect
        val left = panel.left + (panel.width() - (count * tileWidth + (count - 1) * gap)) / 2
        val top = panel.top + (panel.height() - (rows * tileHeight + (rows - 1) * gap)) / 2
        paint.color = 0xffd9d4c8.toInt()
        for (row in 0 until rows) {
            for (column in 0 until count) {
                val x = left + column * (tileWidth + gap)
                val y = top + row * (tileHeight + gap)
                canvas.drawRoundRect(
                    x,
                    y,
                    x + tileWidth,
                    y + tileHeight,
                    4 * scale,
                    4 * scale,
                    paint,
                )
            }
        }
    }

    /** A sky square with a green diagonal edge drawn at `factor` times the base pixel density. */
    private fun drawResolution(canvas: Canvas, panel: RectF, factor: Int, scale: Float) {
        val radius = 14 * scale
        val clip = Path().apply { addRoundRect(panel, radius, radius, Path.Direction.CW) }
        val layer = canvas.save()
        canvas.clipPath(clip)
        paint.color = 0xff9cc3e6.toInt()
        canvas.drawRect(panel, paint)
        paint.color = 0xff4c8a3f.toInt()
        val size = panel.width()
        // Edge from 80% down the left side to 20% down the right side.
        fun edge(fraction: Float) = panel.top + size * (.8f - .6f * fraction)
        if (factor >= 4) {
            val green =
                Path().apply {
                    moveTo(panel.left, edge(0f))
                    lineTo(panel.right, edge(1f))
                    lineTo(panel.right, panel.bottom)
                    lineTo(panel.left, panel.bottom)
                    close()
                }
            canvas.drawPath(green, paint)
        } else {
            val cells = factor * 8
            val cell = size / cells
            for (column in 0 until cells) {
                val centre = (column + .5f) / cells
                val steps = Math.round((edge(centre) - panel.top) / cell)
                val x = panel.left + column * cell
                canvas.drawRect(x, panel.top + steps * cell, x + cell + .5f, panel.bottom, paint)
            }
        }
        canvas.restoreToCount(layer)
    }

    private fun drawArt(
        canvas: Canvas,
        hero: Hero,
        art: Bitmap?,
        alpha: Float,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        if (art == null || alpha <= 0) return
        val fit =
            if (hero.icon) 128 * scale / maxOf(art.width, art.height)
            else maxOf(width / art.width, height / art.height)
        val artWidth = art.width * fit
        val artHeight = art.height * fit
        bounds.set(
            (width - artWidth) / 2,
            (height - artHeight) / 2,
            (width + artWidth) / 2,
            (height + artHeight) / 2,
        )
        artPaint.alpha = (255 * alpha).toInt()
        canvas.drawBitmap(art, null, bounds, artPaint)
    }

    private fun drawText(
        canvas: Canvas,
        hero: Hero,
        alpha: Float,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        if (alpha <= 0) return
        val x = 24 * scale
        val opacity = (255 * alpha).toInt()
        var detailY = height - 24 * scale
        paint.typeface = detailFace
        paint.textSize = 14 * scale
        if (hero.played.isNotEmpty()) {
            paint.color = 0xffe9b949.toInt()
            paint.alpha = opacity
            canvas.drawText(hero.played, x, detailY, paint)
            detailY -= paint.fontSpacing
        }
        paint.color = 0xffd9d4c8.toInt()
        paint.alpha = opacity
        val detail =
            TextUtils.ellipsize(hero.detail, paint, width - 2 * x, TextUtils.TruncateAt.END)
                .toString()
        canvas.drawText(detail, x, detailY, paint)
        val titleBottom = detailY + paint.fontMetrics.ascent - 8 * scale
        paint.typeface = titleFace
        paint.color = 0xfffffef8.toInt()
        paint.alpha = opacity
        paint.textSize = 32 * scale
        val titleBaseline = titleBottom - paint.fontMetrics.descent
        val title =
            StaticLayout.Builder.obtain(
                    hero.title,
                    0,
                    hero.title.length,
                    paint,
                    (width - 2 * x).toInt(),
                )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        val lastLine = title.lineCount - 1
        canvas.save()
        canvas.translate(x, titleBaseline - title.getLineBaseline(lastLine))
        title.draw(canvas)
        canvas.restore()
    }

    companion object {
        private val BACKGROUND = Color.rgb(48, 54, 56)
        private val EMPTY = Hero("", "", "")
        private val LAYOUTS = listOf("1", "2")

        /**
         * Decodes `path`, halving its size while it still covers `width` × `height`. Returns null
         * when the file is not an image.
         */
        fun decode(path: String, width: Int, height: Int): Bitmap? {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0) return null
            options.inSampleSize = 1
            while (
                options.outWidth / (options.inSampleSize * 2) >= width &&
                    options.outHeight / (options.inSampleSize * 2) >= height
            ) {
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(path, options)
        }
    }
}
