package io.github.landern.miel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
)

/**
 * The lower screen: artwork with a title and a detail line, drawn with Canvas. A new selection
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
    private val fade = ValueAnimator.ofFloat(0f, 1f).setDuration(300)
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
        if (art !== toArt || next.title != to.title || next.detail != to.detail) {
            if (progress >= .5f) {
                from = to
                fromArt = toArt
            }
            fade.cancel()
            progress = 0f
            fade.start()
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
                intArrayOf(0x00211c15, 0x55211c15, 0xee211c15.toInt()),
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
        val fromAlpha = if (toArt != null && !to.icon) 1f else 1 - progress
        drawArt(canvas, from, fromArt, fromAlpha, 1f, scale, width, height)
        drawArt(canvas, to, toArt, progress, 1.06f - .06f * progress, scale, width, height)
        canvas.drawRect(0f, 0f, width, height, shade)
        drawText(canvas, from, 1 - minOf(1f, 2 * progress), 0f, scale, width, height)
        drawText(canvas, to, progress, (1 - progress) * 8 * scale, scale, width, height)
    }

    private fun drawArt(
        canvas: Canvas,
        hero: Hero,
        art: Bitmap?,
        alpha: Float,
        zoom: Float,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        if (art == null || alpha <= 0) return
        val fit =
            if (hero.icon) {
                128 * scale / maxOf(art.width, art.height)
            } else {
                maxOf(width / art.width, height / art.height)
            }
        val artWidth = art.width * fit * zoom
        val artHeight = art.height * fit * zoom
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
        offset: Float,
        scale: Float,
        width: Float,
        height: Float,
    ) {
        if (alpha <= 0) return
        val x = 24 * scale
        val opacity = (255 * alpha).toInt()
        paint.typeface = titleFace
        paint.color = 0xfffdfaf3.toInt()
        paint.alpha = opacity
        paint.textSize = 32 * scale
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
        canvas.save()
        canvas.translate(x, height - 60 * scale - title.height + offset)
        title.draw(canvas)
        canvas.restore()
        paint.typeface = detailFace
        paint.color = 0xffe5d6bc.toInt()
        paint.alpha = opacity
        paint.textSize = 14 * scale
        val detail =
            TextUtils.ellipsize(hero.detail, paint, width - 2 * x, TextUtils.TruncateAt.END)
        canvas.drawText(detail.toString(), x, height - 24 * scale + offset, paint)
    }

    companion object {
        private val BACKGROUND = Color.rgb(63, 48, 36)
        private val EMPTY = Hero("", "", "")

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
