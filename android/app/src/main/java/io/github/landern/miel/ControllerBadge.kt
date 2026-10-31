package io.github.landern.miel

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val controllerFont = FontFamily(Font(R.font.nunito_extrabold, FontWeight.ExtraBold))

@Composable
internal fun ControllerBadge(
    key: String,
    modifier: Modifier = Modifier,
    face: Color = Miel.background,
) {
    val shoulder = key == "L" || key == "R"
    val shape = if (shoulder) RoundedCornerShape(5.dp) else CircleShape
    val outline = if (face.luminance() < .65f) Miel.ink else Miel.muted
    Box(
        modifier =
            modifier.border(1.2.dp, outline, shape).clearAndSetSemantics {
                contentDescription = "$key button"
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = key,
            style =
                TextStyle(
                    color = Miel.ink,
                    fontFamily = controllerFont,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (shoulder) 12.sp else 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
