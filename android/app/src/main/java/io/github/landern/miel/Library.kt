package io.github.landern.miel

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Shared metrics and palette for the library, its controls and dialogs. */
private object Miel {
    val spaceXs = 4.dp
    val spaceSm = 8.dp
    val inset = 12.dp
    val gap = 16.dp
    val margin = 24.dp
    val control = 44.dp
    val radius = 26.dp
    val tileRadius = 18.dp
    val font =
        FontFamily(
            Font(R.font.nunito_regular, FontWeight.Normal),
            Font(R.font.nunito_semibold, FontWeight.SemiBold),
            Font(R.font.nunito_bold, FontWeight.Bold),
            Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
        )
    val textSmall = 12.sp
    val textBody = 14.sp
    val background = Color(0xFFFBF7EF)
    val surface = Color(0xFFFFFFFF)
    val ink = Color(0xFF4B3928)
    val muted = Color(0xFF817460)
    val line = Color(0xFFE7DECE)
    val pressed = Color(0xFFF2EBDD)
    val honey = Color(0xFFF5BC45)
    val ring = Color(0xFFE8A329)
    val keyInk = Color(0xFF755020)
    val track = Color(0xFFE6D7BC)
    val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val bounce = CubicBezierEasing(0.34f, 1.45f, 0.64f, 1f)
}

/** The settings menu, in order; Pick(index) chooses one. */
val SETTINGS =
    listOf(
        "Game layout",
        "Choose ROM folder",
        "Rescan folder",
        "Scan report",
        "RomM",
    ) +
        Core.entries.map { it.coreName } +
        listOf("Remove downloaded art", "Set as default launcher", "miel updates", "Licenses")

/** A dialog over the library that is not part of the session. */
sealed interface Overlay {
    data class Settings(
        val selected: Int = 0,
        val core: Core? = null,
        val perGame: Boolean = false,
    ) : Overlay

    data class SaveAccess(val game: Game) : Overlay

    data class Update(val text: String, val action: String? = null) : Overlay

    data class Message(
        val title: String,
        val text: String,
        val scroll: ScrollState = ScrollState(0),
    ) : Overlay

    /** The RomM server's address, typed before pairing. */
    data class RomMAddress(val address: TextFieldState = TextFieldState("https://")) : Overlay

    /** A pairing waiting for approval: its code and a QR code of the approval link. */
    data class RomMPairing(val code: String, val link: String, val qr: ImageBitmap) : Overlay

    data class RomMPaired(val server: String) : Overlay

    /** A game's in-game save changed here and on RomM since they last synced. */
    data class SaveConflict(val title: String, val here: String, val romm: String) : Overlay
}

/** `text` as a QR code, one pixel per module with a four-module margin. */
fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF3F3024.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return bitmap.asImageBitmap()
}

/** A game tile's art: a box cover, or a screenshot that the tile names. */
class Cover(val image: ImageBitmap, val boxart: Boolean)

/** Decoded artwork for the tiles; null when there is none yet. */
class Art(val cover: (Game) -> Cover?, val icon: (InstalledApp) -> ImageBitmap?)

/**
 * The library on the upper screen: the library tabs, a grid of game tiles or apps, and the action
 * row. Every input goes to `command`; see Command.
 */
@Composable
fun Library(session: Session, overlay: Overlay?, art: Art, command: (Command) -> Unit) {
    val apps = session.tab == Tab.APPS
    val total = if (apps) session.apps.size else session.order.size
    val position = if (apps) session.appSelected else session.position
    Box(Modifier.fillMaxSize().background(Miel.background).surfaceTexture(SurfaceTexture.Paper)) {
        Column(
            Modifier.fillMaxSize().padding(Miel.margin),
            verticalArrangement = Arrangement.spacedBy(Miel.gap),
        ) {
            Header(session, command)
            when {
                total == 0 -> Empty(session)
                apps -> AppGrid(session, art, command)
                else -> GameGrid(session, art, command)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Miel.line))
            Row(
                Modifier.fillMaxWidth().height(Miel.control),
                horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm),
            ) {
                if (total > 0) {
                    Pill(
                        "X",
                        if (apps) session.appScreen.other().label else "More",
                        minWidth = 110.dp,
                        primary = false,
                    ) {
                        command(Command.X)
                    }
                }
                Row(
                    Modifier.weight(1f).fillMaxSize(),
                    horizontalArrangement =
                        Arrangement.spacedBy(Miel.spaceXs, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (total > 0) {
                        if (total in 2..4) Dots(total, position)
                        Text(
                            "%02d / %02d".format(position + 1, total),
                            Modifier.widthIn(min = 48.dp),
                            Color(0xFF7A5517),
                            Miel.textSmall,
                            FontWeight.Bold,
                            align = TextAlign.Center,
                            letterSpacing = 1.sp,
                            lines = 1,
                        )
                    }
                }
                if (!apps || total > 0) {
                    Pill(
                        "A",
                        when {
                            apps -> "Open"
                            total == 0 && session.games.isNotEmpty() -> "Browse games"
                            total == 0 -> "Choose ROM folder"
                            session.launched -> "Continue"
                            else -> "Play"
                        },
                        minWidth = if (apps) 120.dp else 180.dp,
                    ) {
                        command(Command.A)
                    }
                }
            }
            if (session.error.isNotEmpty()) {
                Text(session.error, color = Color(0xFFBC5062), size = 12.sp)
            }
        }
        if (session.menu && overlay == null) More(session, command)
        when (overlay) {
            is Overlay.Settings -> Settings(overlay, session, command)
            is Overlay.Message -> Message(overlay, command)
            is Overlay.Update -> Update(overlay, command)
            is Overlay.SaveAccess -> SaveAccess(command)
            is Overlay.RomMAddress -> RomMAddress(overlay, command)
            is Overlay.RomMPairing -> RomMPairing(overlay, command)
            is Overlay.RomMPaired -> RomMPaired(overlay, command)
            is Overlay.SaveConflict -> SaveConflict(overlay, command)
            null -> {}
        }
    }
}

@Composable
private fun Header(session: Session, command: (Command) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Miel.control),
        horizontalArrangement = Arrangement.spacedBy(Miel.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tabs = session.tabs
        KeyHint("L") { command(Command.PreviousTab) }
        Segmented(
            tabs.map { it.label },
            tabs.indexOf(session.tab),
            optionWidth = 64.dp,
            modifier = Modifier.weight(1f),
            height = Miel.control,
            scrollState = rememberScrollState(),
        ) {
            command(Command.ChooseTab(tabs[it]))
        }
        KeyHint("R") { command(Command.NextTab) }
        Pressable(Modifier.size(Miel.control), { command(Command.Select) }) { pressed ->
            val background by
                animateColorAsState(if (pressed) Miel.pressed else Color.Transparent, tween(110))
            Box(
                Modifier.fillMaxSize().background(background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(R.drawable.miel_settings),
                    "Settings",
                    Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(if (pressed) Miel.ink else Miel.muted),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Empty(session: Session) {
    val apps = session.tab == Tab.APPS
    val recent = !apps && session.games.isNotEmpty()
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Miel.inset, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                apps -> "No apps"
                recent -> "No recent games"
                else -> "No games"
            },
            color = Miel.ink,
            size = 24.sp,
        )
        if (!apps)
            Text(
                if (recent) "Pick a platform to play your first game."
                else "Choose a ROM folder to add games.",
                color = Miel.muted,
                size = 13.sp,
            )
    }
}

/** A tile's place on the visible page. */
private class Cell(val index: Int, val slot: Int)

/**
 * A scrolling grid that fills `rows` per column, with one outline following the selection's `slot`.
 * A single row is centred vertically; multiple rows rest on the divider below. The next column
 * peeks past the right gutter. `aspect` is the width over height every tile shares, or null to fill
 * the rows.
 */
@Composable
private fun ColumnScope.TileGrid(
    cells: List<Cell>,
    slot: Int,
    rows: Int,
    columns: Int,
    total: Int,
    gap: Dp,
    aspect: Float?,
    above: (@Composable (Int) -> Unit)? = null,
    below: (@Composable (Int) -> Unit)? = null,
    tile: @Composable (index: Int, chosen: Boolean, height: Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().heightIn(min = 100.dp)) {
        val paged = total > columns * rows
        val columnWidth =
            (maxWidth - gap * (if (paged) columns else columns - 1)) /
                (columns + if (paged) 0.3f else 0f)
        val aboveHeight = if (above != null) 24.dp else 0.dp
        val belowHeight = if (below != null) 72.dp else 0.dp
        val availableHeight = (maxHeight - aboveHeight - belowHeight).coerceAtLeast(1.dp)
        val rowHeight = (availableHeight - gap * (rows - 1)) / rows
        val cellWidth = aspect?.let { minOf(columnWidth, rowHeight * it) } ?: columnWidth
        val cellHeight =
            aspect?.let { minOf(rowHeight, cellWidth / it.coerceAtLeast(0.1f)) } ?: rowHeight
        val remaining = maxHeight - cellHeight * rows - gap * (rows - 1)
        val top = if (rows == 1) remaining / 2 else remaining
        val density = LocalDensity.current
        val stride = with(density) { (cellWidth + gap).toPx() }
        val viewport = with(density) { maxWidth.toPx() }
        val tileWidth = with(density) { cellWidth.toPx() }
        val maxScroll =
            (((total + rows - 1) / rows - 1) * stride + tileWidth - viewport).coerceAtLeast(0f)
        var scroll by remember(rows, total) { mutableFloatStateOf(0f) }
        val scrollState = rememberScrollableState { delta ->
            val before = scroll
            scroll = (scroll - delta).coerceIn(0f, maxScroll)
            before - scroll
        }
        LaunchedEffect(slot, rows, total, viewport, stride) {
            val left = slot / rows * stride
            if (left < scroll) scroll = left
            else if (left + tileWidth > scroll + viewport) scroll = left + tileWidth - viewport
            scroll = scroll.coerceIn(0f, maxScroll)
        }
        val scrollDp = with(density) { scroll.toDp() }
        fun x(slot: Int) = (cellWidth + gap) * (slot / rows)
        fun y(slot: Int) =
            if (rows == 1) aboveHeight + (availableHeight - cellHeight) / 2
            else top + (cellHeight + gap) * (slot % rows)
        Box(Modifier.fillMaxSize().clipToBounds().scrollable(scrollState, Orientation.Horizontal)) {
            val visible = cells.filter {
                val left = it.slot / rows * stride
                left + tileWidth >= scroll && left <= scroll + viewport
            }
            for (cell in visible) {
                if (above != null) {
                    Box(
                        Modifier.offset(x(cell.slot) - scrollDp, y(cell.slot) - aboveHeight)
                            .size(cellWidth, aboveHeight),
                    ) {
                        above(cell.index)
                    }
                }
                Box(
                    Modifier.offset(x(cell.slot) - scrollDp, y(cell.slot))
                        .size(cellWidth, cellHeight),
                ) {
                    tile(cell.index, cell.slot == slot, cellHeight)
                }
                if (below != null) {
                    Box(
                        Modifier.offset(x(cell.slot) - scrollDp, y(cell.slot) + cellHeight)
                            .size(cellWidth, belowHeight),
                    ) {
                        below(cell.index)
                    }
                }
            }
            val ringX by animateDpAsState(x(slot), tween(240, easing = Miel.easeOut))
            val ringY by animateDpAsState(y(slot), tween(240, easing = Miel.easeOut))
            Box(
                Modifier.offset(ringX - scrollDp, ringY)
                    .size(cellWidth, cellHeight)
                    .border(2.dp, Miel.ring, RoundedCornerShape(Miel.tileRadius))
                    .padding(2.dp)
                    .border(1.dp, Miel.background, RoundedCornerShape(Miel.tileRadius - 2.dp)),
            )
        }
    }
}

@Composable
private fun ColumnScope.GameGrid(session: Session, art: Art, command: (Command) -> Unit) {
    val columns = session.gameColumns
    val page = session.order
    val slot = session.position
    TileGrid(
        page.mapIndexed { slot, index -> Cell(index, slot) },
        slot,
        session.gameRows,
        columns,
        session.order.size,
        Miel.gap,
        if (session.gameRows == 1) 3f / 4f else 1f,
        above =
            if (session.gameRows == 1)
                { index ->
                    Text(
                        session.games[index].platform,
                        color = Miel.muted,
                        size = Miel.textSmall,
                        lines = 1,
                    )
                }
            else null,
        below =
            if (session.gameRows == 1)
                { index ->
                    val game = session.games[index]
                    Column(
                        Modifier.padding(top = Miel.spaceSm),
                        verticalArrangement = Arrangement.spacedBy(Miel.spaceXs),
                    ) {
                        Text(
                            game.title,
                            color = Miel.ink,
                            size = Miel.textBody,
                            weight = FontWeight.ExtraBold,
                            lineHeight = 18.sp,
                            lines = 2,
                            minLines = 2,
                        )
                        val detail =
                            listOf(if (game.year > 0) "${game.year}" else "", game.genre)
                                .filter { it.isNotEmpty() }
                                .joinToString(" · ")
                        if (detail.isNotEmpty())
                            Text(detail, color = Miel.muted, size = Miel.textSmall, lines = 1)
                    }
                }
            else null,
    ) { index, chosen, _ ->
        val game = session.games[index]
        Tile(
            chosen,
            Color(0xFFF4EFE4),
            Lift(12.dp, 3.dp),
            Lift(24.dp, 9.dp),
            { command(if (chosen) Command.A else Command.Pick(index)) },
        ) {
            val cover = art.cover(game)
            val modifier = Modifier.fillMaxSize()
            if (cover != null)
                Image(
                    cover.image,
                    null,
                    modifier,
                    contentScale = ContentScale.Crop,
                )
            else
                Image(
                    painterResource(R.drawable.miel_unknown),
                    null,
                    modifier,
                    contentScale = ContentScale.Crop,
                )
            if (cover?.boxart != true && session.gameRows != 1) {
                // A box cover names the game; other art takes the title.
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color(0x003F3024),
                                1f to Color(0xD03F3024),
                            ),
                        ),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(
                        game.title,
                        Modifier.padding(Miel.inset),
                        Color.White,
                        Miel.textBody,
                        FontWeight.SemiBold,
                        lines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AppGrid(session: Session, art: Art, command: (Command) -> Unit) {
    val slot = session.appSelected
    val page = session.apps.indices
    TileGrid(
        page.map { Cell(it, it - page.first) },
        slot,
        APP_ROWS,
        APP_COLUMNS,
        session.apps.size,
        Miel.inset,
        null,
    ) { index, chosen, height ->
        val app = session.apps[index]
        Tile(
            chosen,
            Miel.surface,
            Lift(8.dp, 2.dp),
            Lift(16.dp, 6.dp),
            { command(Command.Pick(index)) },
            border = true,
        ) {
            Column(
                Modifier.fillMaxSize().padding(Miel.spaceSm),
                verticalArrangement =
                    Arrangement.spacedBy(Miel.spaceXs, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val size = (height - Miel.spaceSm * 2 - Miel.spaceXs - 16.dp).coerceIn(24.dp, 56.dp)
                val icon = art.icon(app)
                if (icon != null) Image(icon, null, Modifier.size(size))
                else Image(painterResource(R.drawable.miel_unknown), null, Modifier.size(size))
                Text(
                    app.label,
                    color = if (chosen) Color(0xFF5C3B16) else Color(0xFF5F503D),
                    size = Miel.textSmall,
                    weight = FontWeight.SemiBold,
                    align = TextAlign.Center,
                    lines = 1,
                )
            }
        }
    }
}

/** A shadow's blur and downward offset. */
private class Lift(val blur: Dp, val offset: Dp)

/** A tile that rests at `rest` and takes the deeper shadow `lifted` while selected. */
@Composable
private fun Tile(
    chosen: Boolean,
    background: Color,
    rest: Lift,
    lifted: Lift,
    onClick: () -> Unit,
    border: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Miel.tileRadius)
    val spring = tween<Dp>(280, easing = Miel.bounce)
    val blur by animateDpAsState(if (chosen) lifted.blur else rest.blur, spring)
    val offset by animateDpAsState(if (chosen) lifted.offset else rest.offset, spring)
    Box(
        Modifier.fillMaxSize()
            .dropShadow(
                shape,
                Shadow(
                    blur,
                    if (chosen) Color(0x33A8701F) else Color(0x1A74501D),
                    offset = DpOffset(0.dp, offset),
                ),
            )
            .clip(shape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, Miel.line, shape) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick),
    ) {
        content()
    }
}

@Composable
private fun Dots(total: Int, position: Int) {
    Row(Modifier.height(7.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until total) {
            val width by
                animateDpAsState(
                    if (i == position) 18.dp else 7.dp,
                    tween(240, easing = Miel.easeOut),
                )
            Box(
                Modifier.width(width)
                    .height(7.dp)
                    .background(if (i == position) Miel.ring else Color(0xFFE3D6BE), CircleShape),
            )
        }
    }
}

/** Calls `onClick` on a tap without a ripple; `content` gets whether it is pressed. */
@Composable
private fun Pressable(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(modifier.clickable(interaction, null, onClick = onClick)) { content(pressed) }
}

/**
 * A button with its controller key. The face rests on a darker lip and sinks into it while pressed.
 */
@Composable
private fun RowScope.Pill(
    key: String,
    label: String,
    minWidth: Dp = 140.dp,
    primary: Boolean = true,
    destructive: Boolean = false,
    fill: Boolean = false,
    onClick: () -> Unit,
) {
    val lip = 4.dp
    Pressable(if (fill) Modifier.weight(1f) else Modifier, onClick) { pressed ->
        val sink by
            animateDpAsState(
                if (pressed) 3.dp else 0.dp,
                tween(110, easing = CubicBezierEasing(0.34f, 1.5f, 0.64f, 1f)),
            )
        val shadow by animateDpAsState(if (pressed) 1.dp else 3.dp, tween(100))
        val face by
            animateColorAsState(
                when {
                    pressed && destructive -> Color(0xFFF3D3DB)
                    pressed && primary -> Color(0xFFE6A126)
                    pressed -> Miel.pressed
                    destructive -> Color(0xFFFBE8EB)
                    primary -> Miel.honey
                    else -> Color(0xFFFFFCF5)
                },
                tween(110),
            )
        val shape = RoundedCornerShape(Miel.control / 2)
        val size = Modifier.height(Miel.control).widthIn(min = minWidth)
        Box(if (fill) size.fillMaxWidth() else size, contentAlignment = Alignment.Center) {
            Box(
                Modifier.matchParentSize()
                    .padding(top = lip)
                    .dropShadow(
                        shape,
                        Shadow(8.dp, Color(0x2674501D), offset = DpOffset(0.dp, shadow)),
                    )
                    .background(
                        when {
                            destructive -> Color(0xFFE5B3BF)
                            primary -> Color(0xFFD8901C)
                            else -> Miel.track
                        },
                        shape,
                    ),
            )
            Box(
                Modifier.matchParentSize()
                    .padding(bottom = lip)
                    .offset(y = sink)
                    .background(face, shape)
                    .surfaceTexture(SurfaceTexture.Satin, shape)
                    .border(
                        1.dp,
                        when {
                            destructive -> Color(0xFFEDC6CF)
                            primary -> Color(0xFFEDB23F)
                            else -> Color(0xFFEADFCB)
                        },
                        shape,
                    ),
            )
            Row(
                Modifier.fillMaxHeight()
                    .padding(
                        // The bounce overshoots below zero.
                        PaddingValues(
                            Miel.gap,
                            sink.coerceIn(0.dp, lip),
                            Miel.gap,
                            (lip - sink).coerceIn(0.dp, lip),
                        ),
                    ),
                horizontalArrangement =
                    Arrangement.spacedBy(Miel.spaceSm, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Round key bubble with its own small lip.
                Box(
                    Modifier.size(24.dp)
                        .background(
                            when {
                                destructive -> Color(0xFFE5B3BF)
                                primary -> Color(0xFFDE9A2A)
                                else -> Color(0xFFE3CFA8)
                            },
                            CircleShape,
                        ),
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(22.dp)
                            .background(
                                when {
                                    destructive -> Color.White
                                    primary -> Color(0xFFFFF8E6)
                                    else -> Color(0xFFF8ECD3)
                                },
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            color = if (destructive) Color(0xFF9A455A) else Miel.keyInk,
                            size = 11.sp,
                            weight = FontWeight.ExtraBold,
                        )
                    }
                }
                Text(
                    label,
                    color =
                        when {
                            destructive -> Color(0xFF9A455A)
                            primary -> Color(0xFF4B321B)
                            else -> Color(0xFF6E5F4B)
                        },
                    size = Miel.textBody,
                    weight = FontWeight.Bold,
                    lines = 1,
                )
            }
        }
    }
}

/** A controller button hint that also acts on touch. */
@Composable
private fun KeyHint(key: String, onClick: () -> Unit) {
    Pressable(Modifier.size(Miel.control), onClick) { pressed ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(30.dp, 22.dp)
                    .background(
                        if (pressed) Miel.pressed else Color(0xFFFFFCF5),
                        RoundedCornerShape(7.dp),
                    )
                    .surfaceTexture(SurfaceTexture.Satin, RoundedCornerShape(7.dp))
                    .border(1.dp, Color(0xFFE3CFA8), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(key, color = Miel.keyInk, size = 11.sp, weight = FontWeight.ExtraBold)
            }
        }
    }
}

/** One choice out of a few, as a row of segments. */
@Composable
private fun Segmented(
    options: List<String>,
    current: Int,
    optionWidth: Dp? = 44.dp,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    scrollState: ScrollState? = null,
    picked: (Int) -> Unit,
) {
    Row(
        modifier
            .height(height)
            .background(Miel.pressed, RoundedCornerShape(18.dp))
            .border(1.dp, Miel.line, RoundedCornerShape(18.dp))
            .padding(3.dp)
            .then(if (scrollState != null) Modifier.horizontalScroll(scrollState) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, option ->
            val intoView = remember { BringIntoViewRequester() }
            LaunchedEffect(current == i) { if (current == i) intoView.bringIntoView() }
            val segment =
                if (optionWidth == null) Modifier.weight(1f) else Modifier.width(optionWidth)
            Pressable(segment.fillMaxHeight().bringIntoViewRequester(intoView), { picked(i) }) {
                pressed ->
                val background by
                    animateColorAsState(
                        when {
                            i == current -> Miel.honey
                            pressed -> Miel.track
                            else -> Color.Transparent
                        },
                        tween(110),
                    )
                Box(
                    Modifier.fillMaxSize()
                        .background(background, RoundedCornerShape(15.dp))
                        .then(
                            if (i == current || pressed)
                                Modifier.surfaceTexture(
                                    SurfaceTexture.Satin,
                                    RoundedCornerShape(15.dp),
                                )
                            else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        color = if (i == current) Color(0xFF4B321B) else Color(0xFF6E5F4B),
                        size = if (optionWidth == null) Miel.textSmall else Miel.textBody,
                        weight = FontWeight.Bold,
                        lines = 1,
                    )
                }
            }
        }
    }
}

/** A dialog over a scrim that keeps the library inert behind it. */
@Composable
private fun Dialog(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66493922)).clickable(
            remember { MutableInteractionSource() },
            null,
        ) {},
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(Miel.radius + 4.dp)
        Column(
            Modifier.padding(Miel.margin)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                // Cream base under the dialog face.
                .dropShadow(shape, Shadow(0.dp, Color(0xFFEFE2C8), offset = DpOffset(0.dp, 6.dp)))
                .dropShadow(shape, Shadow(28.dp, Color(0x335D401F), offset = DpOffset(0.dp, 16.dp)))
                .background(Miel.surface, shape)
                .border(1.dp, Miel.line, shape)
                .padding(Miel.margin),
            verticalArrangement = Arrangement.spacedBy(Miel.gap),
        ) {
            Text(title, color = Miel.ink, size = 20.sp, weight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun More(session: Session, command: (Command) -> Unit) {
    val confirm = session.confirm
    val settings = session.settings
    Dialog(if (confirm) "Begin again?" else session.game?.title.orEmpty()) {
        if (confirm) {
            Text(
                "Replaces this session. In-game saves are kept.",
                color = Miel.muted,
                size = Miel.textBody,
            )
        } else if (session.game?.core != null) {
            Row(Modifier.fillMaxWidth()) {
                Pill("Y", "Emulator settings", primary = false, fill = true) { command(Command.Y) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill("B", "Cancel", primary = false, fill = true) { command(Command.B) }
            Pill(
                "A",
                if (confirm) "Begin again" else "Start fresh",
                destructive = confirm,
                fill = true,
            ) {
                command(Command.A)
            }
        }
    }
}

@Composable
private fun Settings(overlay: Overlay.Settings, session: Session, command: (Command) -> Unit) {
    val core = overlay.core
    val options = core?.settingOptions.orEmpty()
    val groups =
        if (core == null)
            listOf(
                "Library" to 0..4,
                "Emulators" to (5 until 5 + Core.entries.size),
                "Device & storage" to (5 + Core.entries.size..SETTINGS.lastIndex),
            )
        else
            listOf(
                (if (overlay.perGame) "For this game" else "Defaults for all games") to
                    options.indices,
            )
    Column(
        Modifier.fillMaxSize()
            .background(Miel.background)
            .surfaceTexture(SurfaceTexture.Paper)
            .padding(Miel.margin),
        verticalArrangement = Arrangement.spacedBy(Miel.gap),
    ) {
        Text(core?.coreName ?: "Settings", color = Miel.ink, size = 28.sp, weight = FontWeight.Bold)
        if (core != null) {
            Text(
                if (overlay.perGame) session.game?.title.orEmpty()
                else core.platforms.joinToString(" / "),
                color = Miel.muted,
                size = Miel.textBody,
            )
            Text(
                if (overlay.perGame)
                    "Cycle to Default to follow Settings. Changes apply on next launch."
                else "Changes apply on next launch. Some options need Start fresh.",
                color = Miel.muted,
                size = Miel.textSmall,
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(remember { ScrollState(0) }),
            verticalArrangement = Arrangement.spacedBy(Miel.margin),
        ) {
            groups.forEach { (title, indices) ->
                Column(verticalArrangement = Arrangement.spacedBy(Miel.spaceSm)) {
                    Text(
                        title,
                        Modifier.padding(start = Miel.inset),
                        Miel.muted,
                        Miel.textSmall,
                        FontWeight.SemiBold,
                    )
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(Miel.tileRadius))
                            .background(Miel.surface)
                            .padding(Miel.spaceXs),
                    ) {
                        indices.forEach { i ->
                            val option = options.getOrNull(i)
                            val value =
                                if (core != null && option != null) {
                                    val current =
                                        session
                                            .optionValues(core, overlay.perGame)
                                            .getValue(option.key)
                                    if (overlay.perGame && option.key !in session.gameOverrides())
                                        "Default: ${option.label(current)}"
                                    else option.label(current)
                                } else null
                            SettingsOption(
                                i,
                                overlay.selected == i,
                                session.gameRows,
                                command,
                                label = option?.label ?: SETTINGS[i],
                                value = value,
                                gameLayout = core == null && i == 0,
                            )
                            if (i != indices.last) {
                                Box(
                                    Modifier.padding(horizontal = Miel.inset)
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(Miel.line),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (session.error.isNotEmpty())
            Text(session.error, color = Color(0xFFBC5062), size = Miel.textSmall)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Miel.line))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("B", "Back", primary = false) { command(Command.B) }
            Pill("A", if (core != null || overlay.selected == 0) "Change" else "Select") {
                command(Command.A)
            }
        }
    }
}

@Composable
private fun SettingsOption(
    index: Int,
    chosen: Boolean,
    gameRows: Int,
    command: (Command) -> Unit,
    label: String,
    value: String? = null,
    gameLayout: Boolean = false,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(chosen) { if (chosen) bringIntoView.bringIntoView() }
    val shape = RoundedCornerShape(14.dp)
    Pressable(
        Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView).semantics {
            selected = chosen
        },
        { command(Command.Pick(index)) },
    ) { pressed ->
        val face by
            animateColorAsState(
                when {
                    pressed -> Miel.pressed
                    chosen -> Color(0xFFFFF3D6)
                    else -> Miel.surface
                },
                tween(110),
            )
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(face, shape)
                .then(if (chosen) Modifier.border(2.dp, Miel.ring, shape) else Modifier)
                .padding(horizontal = Miel.inset, vertical = Miel.spaceXs),
            horizontalArrangement = Arrangement.spacedBy(Miel.inset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                Modifier.weight(1f).padding(vertical = Miel.inset),
                Miel.ink,
                Miel.textBody,
                FontWeight.SemiBold,
            )
            if (value != null) {
                Text(
                    value,
                    Modifier.widthIn(max = 180.dp),
                    Miel.keyInk,
                    Miel.textBody,
                    FontWeight.SemiBold,
                    align = TextAlign.End,
                )
            } else if (gameLayout) {
                Segmented(
                    listOf("1 row", "2 rows"),
                    gameRows - 1,
                    optionWidth = 60.dp,
                    height = 54.dp,
                ) {
                    command(Command.GameRows(it + 1))
                }
            } else {
                Canvas(Modifier.size(16.dp)) {
                    val color = if (chosen) Miel.keyInk else Miel.muted
                    drawLine(
                        color,
                        Offset(size.width * .35f, size.height * .2f),
                        Offset(size.width * .65f, size.height * .5f),
                        2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                    drawLine(
                        color,
                        Offset(size.width * .65f, size.height * .5f),
                        Offset(size.width * .35f, size.height * .8f),
                        2.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveAccess(command: (Command) -> Unit) {
    Dialog("Allow access for game saves") {
        Text(
            "miel needs Android’s All files access to store your saves and checkpoints in the " +
                "miel folder. They stay there if you reinstall the app.\n\n" +
                "On the next screen, enable Allow access to manage all files, then go back " +
                "to start your game.",
            color = Miel.ink,
            size = Miel.textBody,
            lineHeight = 22.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill("B", "Cancel", primary = false, fill = true) { command(Command.B) }
            Pill("A", "Open settings", fill = true) { command(Command.A) }
        }
    }
}

@Composable
private fun Update(overlay: Overlay.Update, command: (Command) -> Unit) {
    Dialog("miel updates") {
        Text(overlay.text, color = Miel.ink, size = Miel.textBody, lineHeight = 22.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill(
                "B",
                if (overlay.action == null) "Cancel" else "Back",
                primary = false,
                fill = true,
            ) {
                command(Command.B)
            }
            overlay.action?.let { action ->
                Pill("A", action, fill = true) { command(Command.A) }
            }
        }
    }
}

@Composable
private fun Message(overlay: Overlay.Message, command: (Command) -> Unit) {
    Dialog(overlay.title) {
        Text(
            overlay.text,
            Modifier.weight(1f, fill = false).verticalScroll(overlay.scroll),
            Miel.ink,
            Miel.textBody,
            lineHeight = 22.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill("B", "Close", primary = false) { command(Command.B) }
        }
    }
}

@Composable
private fun RomMAddress(overlay: Overlay.RomMAddress, command: (Command) -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog("RomM") {
        Text(
            "Your RomM server’s address. miel then shows a code to approve in RomM.",
            color = Miel.muted,
            size = Miel.textBody,
        )
        BasicTextField(
            overlay.address,
            Modifier.fillMaxWidth()
                .border(2.dp, Miel.ring, RoundedCornerShape(Miel.inset))
                .padding(Miel.inset)
                .focusRequester(focus),
            textStyle = TextStyle(color = Miel.ink, fontSize = Miel.textBody),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            onKeyboardAction = { command(Command.A) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill("B", "Cancel", primary = false, fill = true) { command(Command.B) }
            Pill("A", "Connect", fill = true) { command(Command.A) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun RomMPairing(overlay: Overlay.RomMPairing, command: (Command) -> Unit) {
    Dialog("Approve miel in RomM") {
        Row(horizontalArrangement = Arrangement.spacedBy(Miel.gap)) {
            Image(
                overlay.qr,
                null,
                Modifier.size(160.dp),
                filterQuality = FilterQuality.None,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Miel.spaceXs)) {
                Text(
                    "Scan with your phone, or open this address and enter the code:",
                    color = Miel.muted,
                    size = Miel.textBody,
                )
                Text(overlay.link.substringBefore('?'), color = Miel.ink, size = Miel.textSmall)
                Text(
                    overlay.code,
                    color = Miel.ink,
                    size = 28.sp,
                    weight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill("B", "Cancel", primary = false) { command(Command.B) }
        }
    }
}

@Composable
private fun RomMPaired(overlay: Overlay.RomMPaired, command: (Command) -> Unit) {
    Dialog("RomM") {
        Text(
            "Paired with ${overlay.server}. Its games are in the library, and their saves sync" +
                " when you play.",
            color = Miel.muted,
            size = Miel.textBody,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill("X", "Unpair", primary = false, destructive = true, fill = true) {
                command(Command.X)
            }
            Pill("B", "Close", primary = false, fill = true) { command(Command.B) }
        }
    }
}

@Composable
private fun SaveConflict(overlay: Overlay.SaveConflict, command: (Command) -> Unit) {
    Dialog("Which save?") {
        Text(
            "${overlay.title} was saved here (${overlay.here}) and on RomM (${overlay.romm})" +
                " since they last synced. The other save is kept as a backup.",
            color = Miel.muted,
            size = Miel.textBody,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.inset)) {
            Pill("X", "Keep this one", primary = false, fill = true) { command(Command.X) }
            Pill("A", "Use RomM’s", fill = true) { command(Command.A) }
        }
    }
}

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color,
    size: TextUnit,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    lines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = Miel.font,
            textAlign = align,
            letterSpacing = letterSpacing,
            lineHeight = lineHeight,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = lines,
        minLines = minLines,
    )
}
