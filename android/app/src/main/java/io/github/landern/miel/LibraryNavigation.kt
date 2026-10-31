package io.github.landern.miel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Console silhouettes and collection pictograms in shoulder-button order. */
@Composable
internal fun LibraryNavigation(
    tabs: List<Tab>,
    current: Tab,
    modifier: Modifier = Modifier,
    picked: (Tab) -> Unit,
) {
    Row(
        modifier
            .height(64.dp)
            .relief(RoundedCornerShape(24.dp), Miel.pressed, inset = true)
            .padding(6.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab == current
            val intoView = remember(tab) { BringIntoViewRequester() }
            LaunchedEffect(active) { if (active) intoView.bringIntoView() }
            Pressable(
                Modifier.width(64.dp).height(52.dp).bringIntoViewRequester(intoView).semantics {
                    selected = active
                    contentDescription = tab.platform ?: tab.label
                    role = Role.Tab
                },
                { picked(tab) },
            ) { pressed ->
                val face by
                    animateColorAsState(
                        if (active) Miel.honey else if (pressed) Miel.selection else Miel.pressed,
                        tween(160, easing = Miel.easeOut),
                    )
                val lift by
                    animateFloatAsState(
                        if (active) -2f else 0f,
                        tween(180, easing = Miel.easeOut),
                    )
                Column(
                    Modifier.fillMaxSize()
                        .then(
                            if (active || pressed)
                                Modifier.controlSurface(RoundedCornerShape(19.dp), face, pressed)
                            else Modifier,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Image(
                        painterResource(tab.navigationIcon),
                        null,
                        Modifier.size(38.dp).graphicsLayer { translationY = lift.dp.toPx() },
                        colorFilter = ColorFilter.tint(if (active) Miel.ink else Miel.softInk),
                    )
                }
            }
        }
    }
}

private val Tab.navigationIcon: Int
    get() =
        when (this) {
            Tab.RECENT -> R.drawable.miel_nav_recent
            Tab.FAVORITES -> R.drawable.miel_nav_favorites
            Tab.N3DS -> R.drawable.miel_nav_n3ds
            Tab.NDS -> R.drawable.miel_nav_nds
            Tab.GBA -> R.drawable.miel_nav_gba
            Tab.GBC -> R.drawable.miel_nav_gbc
            Tab.GB -> R.drawable.miel_nav_gb
            Tab.NES -> R.drawable.miel_nav_nes
            Tab.SNES -> R.drawable.miel_nav_snes
            Tab.PSX -> R.drawable.miel_nav_psx
            Tab.PSP -> R.drawable.miel_nav_psp
            Tab.GAMECUBE -> R.drawable.miel_nav_gamecube
            Tab.PC -> R.drawable.miel_nav_pc
            Tab.APPS -> R.drawable.miel_nav_apps
        }
