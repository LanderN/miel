package io.github.landern.miel

import android.content.Context
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The lower screen during single-screen play: the game's art, title and play time, the time and
 * battery, and fast-forward, Library, PlayStation controller and disc controls. Disc, fast-forward
 * and save-state controls open as sheets over it.
 */
class PlayControls(
    context: Context,
    private val title: String?,
    private val opened: () -> Unit,
    private val dismissed: () -> Unit,
    private val insert: (Int) -> Unit,
    private val toggleAnalog: () -> Unit,
    private val toggleFastForward: () -> Unit,
    fastForwardSettings: Map<String, String>,
    private val changeFastForward: (key: String, value: String) -> Unit,
    private val stateAction: (slot: Int, load: Boolean) -> Unit,
    private val leave: () -> Unit,
    private val resume: () -> Unit,
) : AbstractComposeView(context) {
    private enum class Sheet {
        DISCS,
        FAST_FORWARD,
        STATES,
    }

    /** Slot 0 is Auto save; slots 1–5 are manual. A zero timestamp denotes an empty slot. */
    data class StateSlot(val index: Int, val modified: Long)

    private data class Status(
        val count: Int = 0,
        val index: Int = 0,
        val popup: Boolean = false,
        val failed: Boolean = false,
        val showAnalog: Boolean = false,
        val analogEnabled: Boolean = true,
        val fastForwarding: Boolean = false,
        val played: String = "",
        val battery: String = "",
    )

    private var status by mutableStateOf(Status())
    private var sheet by mutableStateOf<Sheet?>(null)
    private var highlighted by mutableIntStateOf(0)
    private var settings by mutableStateOf(fastForwardSettings)
    private var art by mutableStateOf<ImageBitmap?>(null)
    private var stateSlots by mutableStateOf((0..5).map { StateSlot(it, 0) })
    private var stateBusy by mutableStateOf(false)
    private var stateMessage by mutableStateOf("")
    private var stateConfirmation by mutableStateOf<Boolean?>(null)
    private var batteryAt = 0L
    private val paper = Color(0xFFFFFEF8)

    val isOpen
        get() = sheet != null

    init {
        visibility = GONE
        isClickable = true
    }

    fun art(bitmap: Bitmap) {
        art = bitmap.asImageBitmap()
    }

    /**
     * `played` is the game's total play time in milliseconds. A `popup` covers a dual-screen game's
     * lower screen and offers Resume.
     */
    fun update(
        count: Int,
        index: Int,
        ready: Boolean,
        popup: Boolean,
        failed: Boolean,
        showAnalog: Boolean,
        analogEnabled: Boolean,
        fastForwarding: Boolean,
        played: Long,
    ) {
        visibility = if (ready) VISIBLE else GONE
        var battery = status.battery
        val now = SystemClock.uptimeMillis()
        if (batteryAt == 0L || now - batteryAt > 30_000) {
            batteryAt = now
            val level =
                context
                    .getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            battery = if (level in 0..100) "$level%" else ""
        }
        status =
            Status(
                count,
                index,
                popup,
                failed,
                showAnalog,
                analogEnabled,
                fastForwarding,
                "${PlayTime.duration(played)} played",
                battery,
            )
    }

    fun updateStates(slots: List<StateSlot>, busy: Boolean, message: String) {
        stateSlots =
            (0..5).map { index -> slots.firstOrNull { it.index == index } ?: StateSlot(index, 0) }
        stateBusy = busy
        stateMessage = message
    }

    /** Opens the disc chooser. */
    fun open() {
        if (status.count < 2 || isOpen) return
        opened()
        highlighted = status.index.coerceIn(0, status.count - 1)
        sheet = Sheet.DISCS
    }

    private fun openSettings() {
        if (isOpen) return
        opened()
        highlighted = 0
        sheet = Sheet.FAST_FORWARD
    }

    private fun openStates() {
        if (isOpen) return
        highlighted = 0
        stateConfirmation = null
        sheet = Sheet.STATES
        opened()
    }

    fun close() {
        if (!isOpen || stateBusy) return
        stateConfirmation = null
        sheet = null
        dismissed()
    }

    fun key(event: KeyEvent): Boolean {
        val sheet = sheet ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || stateBusy) return true
        val first = event.repeatCount == 0
        if (sheet == Sheet.STATES && stateConfirmation != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER -> if (first) performState(stateConfirmation!!)
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> if (first) stateConfirmation = null
            }
            return true
        }
        val rows =
            when (sheet) {
                Sheet.DISCS -> status.count
                Sheet.FAST_FORWARD -> FAST_FORWARD_OPTIONS.size
                Sheet.STATES -> stateSlots.size
            }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> highlighted = (highlighted - 1 + rows) % rows
            KeyEvent.KEYCODE_DPAD_DOWN -> highlighted = (highlighted + 1) % rows
            KeyEvent.KEYCODE_DPAD_LEFT -> if (sheet == Sheet.FAST_FORWARD) step(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (sheet == Sheet.FAST_FORWARD) step(1)
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER ->
                if (first)
                    when (sheet) {
                        Sheet.DISCS -> insert()
                        Sheet.FAST_FORWARD -> close()
                        Sheet.STATES -> requestState(load = true)
                    }
            KeyEvent.KEYCODE_BUTTON_X ->
                if (first && sheet == Sheet.STATES) requestState(load = false)
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (first) close()
        }
        return true
    }

    private fun requestState(load: Boolean) {
        if (stateBusy) return
        val slot = stateSlots[highlighted]
        if ((load && slot.modified == 0L) || (!load && slot.index == 0)) return
        if (load || slot.modified != 0L) stateConfirmation = load else performState(load = false)
    }

    private fun performState(load: Boolean) {
        if (stateBusy) return
        stateConfirmation = null
        stateBusy = true
        stateMessage = if (load) "Loading…" else "Saving…"
        stateAction(stateSlots[highlighted].index, load)
    }

    private fun insert() {
        insert(highlighted)
        close()
    }

    /** Moves the highlighted fast-forward setting `step` values along, stopping at either end. */
    private fun step(step: Int) {
        val option = FAST_FORWARD_OPTIONS[highlighted]
        val current = option.values.indexOf(settings.getValue(option.key))
        pick(option, (current + step).coerceIn(0, option.values.lastIndex))
    }

    private fun pick(option: CoreOption, value: Int) {
        val chosen = option.values[value]
        if (settings[option.key] == chosen) return
        settings = settings + (option.key to chosen)
        changeFastForward(option.key, chosen)
    }

    private fun label(key: String): String {
        val option = FAST_FORWARD_OPTIONS.first { it.key == key }
        return option.label(settings.getValue(key))
    }

    @Composable
    override fun Content() {
        Box(Modifier.fillMaxSize().background(Color(0xFF303638))) {
            art?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xB3303638), Color(0xD9303638), Color(0xFA303638)),
                        ),
                    ),
            )
            when (sheet) {
                null -> Dock()
                Sheet.DISCS -> Discs()
                Sheet.FAST_FORWARD -> FastForward()
                Sheet.STATES -> States()
            }
        }
    }

    @Composable
    private fun Dock() {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = Miel.margin, top = Miel.gap, end = Miel.gap),
                horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Clock()
                Text(
                    status.battery,
                    Modifier.weight(1f).padding(start = Miel.spaceXs),
                    paper.copy(alpha = .8f),
                    Miel.textBody,
                )
                Pill(null, "Library", minWidth = 0.dp, primary = false) { leave() }
                if (status.popup) Pill("B", "Resume", minWidth = 0.dp) { resume() }
            }
            Spacer(Modifier.weight(1f))
            if (!title.isNullOrEmpty()) {
                Text(
                    title,
                    Modifier.widthIn(max = 420.dp),
                    paper,
                    24.sp,
                    FontWeight.Bold,
                    align = TextAlign.Center,
                    lines = 2,
                )
            }
            Text(
                status.played,
                Modifier.padding(top = Miel.spaceXs, bottom = Miel.gap),
                Miel.honey,
                Miel.textBody,
                FontWeight.SemiBold,
            )
            Column(
                Modifier.padding(bottom = Miel.margin)
                    .width(380.dp)
                    .relief(RoundedCornerShape(Miel.radius), Miel.surface, height = 1.4f)
                    .padding(horizontal = Miel.spaceXs, vertical = Miel.spaceXs),
            ) {
                if (status.count > 1) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(start = Miel.inset, end = Miel.spaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (status.failed) "Couldn’t change disc · Retry"
                            else "Disc ${status.index + 1} of ${status.count}",
                            Modifier.weight(1f),
                            Miel.ink,
                            Miel.textBody,
                            FontWeight.SemiBold,
                        )
                        Pill("R3", "Change disc", minWidth = 0.dp) { open() }
                    }
                }
                if (status.showAnalog) {
                    ToggleRow("Analog controls", status.analogEnabled, toggleAnalog)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pressable(Modifier.weight(1f), toggleFastForward) { pressed ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .then(
                                    if (pressed)
                                        Modifier.relief(
                                            RoundedCornerShape(14.dp),
                                            Miel.pressed,
                                            inset = true,
                                        )
                                    else Modifier,
                                )
                                .padding(horizontal = Miel.inset),
                            horizontalArrangement = Arrangement.spacedBy(Miel.inset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Fast-forward",
                                Modifier.weight(1f),
                                Miel.ink,
                                Miel.textBody,
                                FontWeight.SemiBold,
                            )
                            Toggle(status.fastForwarding)
                        }
                    }
                    Pressable(
                        Modifier.semantics { contentDescription = "Fast-forward settings" },
                        ::openSettings,
                    ) { pressed ->
                        Row(
                            Modifier.heightIn(min = 56.dp)
                                .then(
                                    if (pressed)
                                        Modifier.relief(
                                            RoundedCornerShape(14.dp),
                                            Miel.pressed,
                                            inset = true,
                                        )
                                    else Modifier,
                                )
                                .padding(horizontal = Miel.inset),
                            horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label("speed"),
                                color = Miel.muted,
                                size = Miel.textBody,
                                weight = FontWeight.SemiBold,
                            )
                            Image(
                                painterResource(R.drawable.miel_settings),
                                null,
                                Modifier.size(24.dp),
                                colorFilter = ColorFilter.tint(Miel.ink),
                            )
                        }
                    }
                }
                Pressable(Modifier.fillMaxWidth(), ::openStates) { pressed ->
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .then(
                                if (pressed)
                                    Modifier.relief(
                                        RoundedCornerShape(14.dp),
                                        Miel.pressed,
                                        inset = true,
                                    )
                                else Modifier,
                            )
                            .padding(horizontal = Miel.inset),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Save states",
                            Modifier.weight(1f),
                            Miel.ink,
                            Miel.textBody,
                            FontWeight.SemiBold,
                        )
                        Chevron(Miel.muted)
                    }
                }
            }
        }
    }

    @Composable
    private fun Clock() {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000 - now % 60_000)
                now = System.currentTimeMillis()
            }
        }
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        Text(
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)),
            color = paper,
            size = 16.sp,
            weight = FontWeight.Bold,
        )
    }

    @Composable
    private fun ToggleRow(label: String, checked: Boolean, toggled: () -> Unit) {
        Pressable(Modifier.fillMaxWidth(), toggled) { pressed ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(
                        if (pressed)
                            Modifier.relief(RoundedCornerShape(14.dp), Miel.pressed, inset = true)
                        else Modifier,
                    )
                    .padding(horizontal = Miel.inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), Miel.ink, Miel.textBody, FontWeight.SemiBold)
                Toggle(checked)
            }
        }
    }

    /** A switch: an inset track, honey when on, with a raised thumb. */
    @Composable
    private fun Toggle(checked: Boolean) {
        val offset by
            animateDpAsState(if (checked) 22.dp else 0.dp, tween(160, easing = Miel.easeOut))
        Box(
            Modifier.size(52.dp, 30.dp)
                .relief(
                    RoundedCornerShape(15.dp),
                    if (checked) Miel.honey else Miel.pressed,
                    inset = true,
                )
                .padding(3.dp),
        ) {
            Box(
                Modifier.offset(x = offset)
                    .size(24.dp)
                    .relief(CircleShape, Miel.surface, height = .6f),
            )
        }
    }

    /** A full-screen sheet in the library's Settings layout. */
    @Composable
    private fun Sheet(
        title: String,
        message: String = "",
        footer: @Composable RowScope.() -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().background(Miel.background).padding(Miel.margin),
            verticalArrangement = Arrangement.spacedBy(Miel.gap),
        ) {
            Text(title, color = Miel.ink, size = 28.sp, weight = FontWeight.Bold)
            if (message.isNotEmpty()) Text(message, color = Miel.ink, size = Miel.textBody)
            Column(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .scrollFades(scroll, Miel.background)
                    .verticalScroll(scroll, enabled = !stateBusy)
                    .padding(horizontal = 6.dp, vertical = Miel.spaceSm),
                content = content,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                content = footer,
            )
        }
    }

    private fun slotLabel(slot: StateSlot) =
        if (slot.index == 0) "Auto save" else "Slot ${slot.index}"

    @Composable
    private fun States() {
        val slot = stateSlots[highlighted]
        val confirmation = stateConfirmation
        Sheet(
            if (confirmation == null) "Save states"
            else if (confirmation) "Load ${slotLabel(slot).lowercase()}?"
            else "Overwrite ${slotLabel(slot).lowercase()}?",
            message = if (confirmation == null) stateMessage else "",
            footer = {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Miel.control),
                    horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm),
                ) {
                    if (!stateBusy) {
                        Pill(
                            "B",
                            if (confirmation == null) "Back" else "Cancel",
                            minWidth = 0.dp,
                            primary = false,
                            fill = true,
                        ) {
                            if (confirmation == null) close() else stateConfirmation = null
                        }
                        if (confirmation != null) {
                            Pill(
                                "A",
                                if (confirmation) "Load" else "Overwrite",
                                minWidth = 0.dp,
                                fill = true,
                            ) {
                                performState(confirmation)
                            }
                        } else {
                            if (slot.index > 0)
                                Pill("X", "Save", minWidth = 0.dp, primary = false, fill = true) {
                                    requestState(load = false)
                                }
                            if (slot.modified > 0)
                                Pill("A", "Load", minWidth = 0.dp, fill = true) {
                                    requestState(load = true)
                                }
                        }
                    }
                }
            },
        ) {
            if (confirmation != null) {
                Text(
                    if (confirmation) "Replaces current progress."
                    else "Replaces the saved state in this slot.",
                    color = Miel.muted,
                    size = Miel.textBody,
                )
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = Miel.spaceXs),
                    verticalArrangement = Arrangement.spacedBy(Miel.spaceSm),
                ) {
                    stateSlots.forEach { StateRow(it) }
                }
            }
        }
    }

    @Composable
    private fun StateRow(slot: StateSlot) {
        val chosen = highlighted == slot.index
        val bringIntoView = remember { BringIntoViewRequester() }
        var rowSize by remember { mutableStateOf(IntSize.Zero) }
        val clearance = with(LocalDensity.current) { Miel.inset.toPx() }
        LaunchedEffect(chosen, rowSize, clearance, stateMessage) {
            if (chosen && rowSize.height > 0)
                bringIntoView.bringIntoView(
                    Rect(0f, -clearance, rowSize.width.toFloat(), rowSize.height + clearance),
                )
        }
        Pressable(
            Modifier.fillMaxWidth()
                .onSizeChanged { rowSize = it }
                .bringIntoViewRequester(bringIntoView)
                .semantics { selected = chosen },
            { if (!stateBusy) highlighted = slot.index },
        ) { pressed ->
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .relief(
                        RoundedCornerShape(14.dp),
                        if (chosen) Miel.selection else Miel.surface,
                        height = .5f,
                        inset = pressed && !stateBusy,
                    )
                    .padding(horizontal = Miel.inset, vertical = Miel.spaceSm),
                verticalArrangement = Arrangement.spacedBy(Miel.spaceXs),
            ) {
                Text(
                    slotLabel(slot),
                    color = Miel.ink,
                    size = Miel.textBody,
                    weight = FontWeight.Bold,
                )
                val modified =
                    if (slot.modified == 0L) "Empty"
                    else {
                        val date = Date(slot.modified)
                        "${DateFormat.getDateFormat(context).format(date)} · ${DateFormat.getTimeFormat(context).format(date)}"
                    }
                Text(modified, color = Miel.muted, size = Miel.textSmall)
            }
        }
    }

    @Composable
    private fun Discs() {
        Sheet(
            "Change disc",
            footer = {
                Pill("B", "Cancel", primary = false) { close() }
                Pill("A", "Insert disc") { insert() }
            },
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .relief(RoundedCornerShape(Miel.tileRadius), Miel.background, inset = true)
                    .padding(Miel.spaceXs),
                verticalArrangement = Arrangement.spacedBy(Miel.spaceXs),
            ) {
                repeat(status.count) { disc ->
                    MoreRow(
                        "Disc ${disc + 1}" + if (disc == status.index) "   ·   Inserted" else "",
                        disc == highlighted,
                        false,
                    ) {
                        highlighted = disc
                        insert()
                    }
                }
            }
        }
    }

    @Composable
    private fun FastForward() {
        Sheet("Fast-forward", footer = { Pill("B", "Done", primary = false) { close() } }) {
            Column(
                Modifier.fillMaxWidth()
                    .relief(RoundedCornerShape(Miel.tileRadius), Miel.surface, height = .7f)
                    .padding(Miel.spaceXs),
            ) {
                FAST_FORWARD_OPTIONS.forEachIndexed { row, option ->
                    val chosen = row == highlighted
                    Pressable(
                        Modifier.fillMaxWidth().semantics { selected = chosen },
                        { highlighted = row },
                    ) { pressed ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .then(
                                    if (chosen || pressed)
                                        Modifier.relief(
                                            RoundedCornerShape(14.dp),
                                            if (pressed) Miel.pressed else Miel.selection,
                                            inset = true,
                                        )
                                    else Modifier,
                                )
                                .padding(horizontal = Miel.inset, vertical = Miel.spaceSm),
                            horizontalArrangement = Arrangement.spacedBy(Miel.inset),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                Modifier.weight(1f),
                                Miel.ink,
                                Miel.textBody,
                                FontWeight.SemiBold,
                            )
                            Segmented(
                                option.labels,
                                option.values.indexOf(settings.getValue(option.key)),
                                optionWidth =
                                    if (option.labels.any { it.length > 4 }) 76.dp else 52.dp,
                                height = 40.dp,
                            ) {
                                highlighted = row
                                pick(option, it)
                            }
                        }
                    }
                }
            }
        }
    }
}
