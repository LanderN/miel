package io.github.landern.miel

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The lower screen while another app covers the library: the apps, black until touched. A touch on
 * the black screen only wakes it; a tile tapped while awake opens its app.
 */
class AppSwitcher(
    context: Context,
    private val apps: () -> List<InstalledApp>,
    private val icon: (InstalledApp) -> ImageBitmap?,
    private val open: (InstalledApp) -> Unit,
) : AbstractComposeView(context) {
    private var awake by mutableStateOf(false)

    /** Counts touches, restarting the dimming delay. */
    private var touches by mutableIntStateOf(0)

    /** Whether the grid was awake when the current touch began. */
    private var wasAwake = false

    @Composable
    override fun Content() {
        LaunchedEffect(touches) {
            delay(8_000)
            awake = false
        }
        Column(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        wasAwake = awake
                        awake = true
                        touches++
                    }
                }
                .alpha(if (awake) 1f else 0f)
                .verticalScroll(rememberScrollState())
                .padding(Miel.margin),
            verticalArrangement = Arrangement.spacedBy(Miel.spaceSm),
        ) {
            apps().chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm)) {
                    row.forEach { app ->
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            Tile(false, Miel.surface, { if (wasAwake) open(app) }, border = true) {
                                Column(
                                    Modifier.fillMaxSize().padding(Miel.spaceSm),
                                    verticalArrangement =
                                        Arrangement.spacedBy(
                                            Miel.spaceXs,
                                            Alignment.CenterVertically,
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val image = icon(app)
                                    if (image != null) Image(image, null, Modifier.size(48.dp))
                                    else
                                        Image(
                                            painterResource(R.drawable.miel_unknown),
                                            null,
                                            Modifier.size(48.dp),
                                        )
                                    Text(
                                        app.label,
                                        color = Miel.muted,
                                        size = Miel.textSmall,
                                        weight = FontWeight.SemiBold,
                                        align = TextAlign.Center,
                                        lines = 1,
                                    )
                                }
                            }
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 5
    }
}
