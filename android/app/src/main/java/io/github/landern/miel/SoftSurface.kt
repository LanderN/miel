package io.github.landern.miel

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Contact and cast shadows from an upper-left light. Height is relative to a control face. */
internal fun Modifier.softShadow(shape: Shape, height: Float = 1f): Modifier =
    dropShadow(
            shape,
            Shadow(1.2.dp, Color(0x24654A28), offset = DpOffset(.5.dp, 1.dp * height)),
        )
        .dropShadow(
            shape,
            Shadow(
                3.dp * height,
                Color(0x16654A28),
                offset = DpOffset(1.dp * height, 2.dp * height),
            ),
        )
        .dropShadow(
            shape,
            Shadow(
                7.dp * height,
                Color(0x14654A28),
                offset = DpOffset(3.dp * height, 5.dp * height),
            ),
        )

/** Smooth relief without outlines; inset surfaces collect shadow at the upper edge. */
internal fun Modifier.relief(
    shape: Shape,
    face: Color,
    height: Float = 1f,
    inset: Boolean = false,
): Modifier {
    val base = if (inset) this else softShadow(shape, height)
    val surface =
        base.background(
            Brush.linearGradient(
                listOf(lerp(face, Color.White, .025f), lerp(face, Color(0xFF6B5035), .015f)),
            ),
            shape,
        )
    return if (inset)
        surface
            .innerShadow(
                shape,
                Shadow(3.dp, Color(0x2667502F), offset = DpOffset(1.dp, 1.5.dp)),
            )
            .innerShadow(
                shape,
                Shadow(1.dp, Color(0x33FFF3D8), offset = DpOffset(-.4.dp, -.6.dp)),
            )
    else surface
}
