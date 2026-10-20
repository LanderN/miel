package io.github.landern.miel

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Static, low-contrast materials. Tiles are shared; shaders and outlines are cached per surface. */
internal enum class SurfaceTexture {
    Paper,
    Satin,
}

/** Apply after the background and before borders/padding; the grain stays behind all content. */
internal fun Modifier.surfaceTexture(
    texture: SurfaceTexture,
    shape: Shape = RectangleShape,
): Modifier = drawWithCache {
    val bitmap = if (texture == SurfaceTexture.Paper) TextureTiles.paper else TextureTiles.satin
    val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    // Two texels per dp: the material has the same fine scale on either Thor display.
    shader.setLocalMatrix(Matrix().apply { setScale(density / 2f, density / 2f) })
    val brush = ShaderBrush(shader)
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind { drawOutline(outline, brush) }
}

private object TextureTiles {
    private const val SIZE = 256

    val paper: Bitmap by lazy { tile(satin = false) }
    val satin: Bitmap by lazy { tile(satin = true) }

    private fun tile(satin: Boolean): Bitmap {
        val random = Random(if (satin) 731 else 419)
        val noise = FloatArray(SIZE * SIZE) { random.nextFloat() * 2f - 1f }
        fun sample(x: Int, y: Int) = noise[((y + SIZE) % SIZE) * SIZE + (x + SIZE) % SIZE]
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val grain =
                    if (satin) {
                        // Short, irregular horizontal strands, not full-width scan lines.
                        var strand = 0f
                        for (dx in -12..12) strand += sample(x + dx, y)
                        strand / 25f * 0.85f + sample(x, y) * 0.15f
                    } else {
                        // Fine speckle with a softer, organic paper tooth underneath.
                        var tooth = 0f
                        for (dy in -2..2) {
                            for (dx in -2..2) tooth += sample(x + dx, y + dy)
                        }
                        sample(x, y) * 0.55f + tooth / 25f * 0.45f
                    }
                // White flecks and warm brown shadows preserve the original surface palette.
                val strength = if (satin) 42f else 24f
                val alpha = (abs(grain) * strength).roundToInt().coerceIn(0, 20)
                val rgb = if (grain >= 0f) 0xFFFFFF else 0x6B5035
                pixels[y * SIZE + x] = (alpha shl 24) or rgb
            }
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}
