package io.github.landern.miel

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State and IO for one game's code list. */
class GameCodesEditor(context: Context, val game: Game, private val scope: CoroutineScope) {
    private val root = File(context.filesDir, "game-codes")
    var disc by mutableStateOf<GameCubeDisc?>(null)
        private set

    var codes by mutableStateOf(emptyList<GameCodes.Code>())
        private set

    var selected by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("")
        private set

    var editing by mutableStateOf(false)
        private set

    var name = TextFieldState()
        private set

    var body = TextFieldState()
        private set

    var format by mutableStateOf(GameCodes.Format.GECKO)
    private var editIndex: Int? = null

    init {
        run {
            val uri =
                if (game.remote) {
                    val file = SaveStorage.downloadFile(game.platform, game.romFilename)
                    if (!file.isFile) throw IOException("Download the game before adding codes")
                    Uri.fromFile(file)
                } else Uri.parse(game.romUri)
            val result =
                withContext(Dispatchers.IO) {
                    val disc =
                        context.contentResolver.openInputStream(uri)?.buffered()?.use {
                            GameCubeDisc.read(it, game.romFilename)
                        } ?: throw IOException("Cannot read game")
                    val catalog =
                        context.assets.open("gamecube-widescreen.json").bufferedReader().use {
                            GameCodes.catalog(it.readText(), disc)
                        }
                    disc to GameCodes.merge(GameCodes.load(root, disc), catalog)
                }
            disc = result.first
            codes = result.second
        }
    }

    private fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = ""
        scope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message ?: "Could not update game codes"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun save(next: List<GameCodes.Code>) {
        withContext(Dispatchers.IO) { GameCodes.save(root, requireNotNull(disc), next) }
        codes = next
        selected = selected.coerceAtMost((codes.size - 1).coerceAtLeast(0))
    }

    fun toggle(index: Int) {
        if (index !in codes.indices) return
        selected = index
        run {
            save(
                codes.mapIndexed { i, code ->
                    if (i == index) code.copy(enabled = !code.enabled) else code
                },
            )
        }
    }

    fun edit(index: Int? = null) {
        if (busy || disc == null) return
        val code = index?.let(codes::getOrNull)
        if (code?.widescreen == true) return
        editIndex = index
        name = TextFieldState(code?.name.orEmpty())
        body = TextFieldState(code?.body.orEmpty())
        format = code?.format ?: GameCodes.Format.GECKO
        status = ""
        editing = true
    }

    fun commit() = run {
        val code = GameCodes.code(name.text.toString(), body.text.toString(), format)
        val index = editIndex
        val next = codes.toMutableList()
        if (index == null) next.add(code)
        else next[index] = code.copy(enabled = codes[index].enabled, notes = codes[index].notes)
        save(next)
        editing = false
    }

    fun delete() = run {
        val index = editIndex ?: return@run
        save(codes.filterIndexed { i, _ -> i != index })
        editing = false
    }

    val canDelete: Boolean
        get() = editIndex != null

    fun command(command: Command, close: () -> Unit) {
        if (command == Command.B) {
            if (busy) return
            if (editing) {
                editing = false
                status = ""
            } else close()
            return
        }
        if (busy) return
        if (editing) {
            when (command) {
                Command.A -> commit()
                Command.X -> if (canDelete) delete()
                Command.Y ->
                    format =
                        if (format == GameCodes.Format.GECKO) GameCodes.Format.ACTION_REPLAY
                        else GameCodes.Format.GECKO
                else -> {}
            }
        } else {
            when (command) {
                Command.Up -> selected = (selected - 1).coerceAtLeast(0)
                Command.Down ->
                    selected = (selected + 1).coerceAtMost((codes.size - 1).coerceAtLeast(0))
                Command.A -> toggle(selected)
                Command.X -> edit()
                Command.NextTab -> if (codes.isNotEmpty()) edit(selected)
                is Command.Pick -> toggle(command.index)
                else -> {}
            }
        }
    }
}

@Composable
fun GameCodesScreen(editor: GameCodesEditor, command: (Command) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(editor.selected) {
        if (editor.codes.isNotEmpty()) list.animateScrollToItem(editor.selected)
    }
    Column(
        Modifier.fillMaxSize().background(Miel.background).imePadding().padding(Miel.margin),
        verticalArrangement = Arrangement.spacedBy(Miel.spaceSm),
    ) {
        Text(
            if (editor.editing) "Edit code" else "Game codes",
            color = Miel.ink,
            size = 28.sp,
            weight = FontWeight.Bold,
        )
        if (!editor.editing)
            Text(
                editor.game.title +
                    (editor.disc?.let { " · ${it.id} · Revision ${it.revision}" } ?: ""),
                color = Miel.muted,
                size = Miel.textSmall,
            )
        if (editor.editing) {
            val focus = remember { FocusRequester() }
            val codeFocus = remember { FocusRequester() }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Miel.spaceSm),
            ) {
                Text("Name", color = Miel.ink, size = Miel.textSmall)
                BasicTextField(
                    editor.name,
                    Modifier.fillMaxWidth().codeField().focusRequester(focus),
                    textStyle = TextStyle(color = Miel.ink, fontSize = Miel.textBody),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions =
                        KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    onKeyboardAction = { codeFocus.requestFocus() },
                )
                Row {
                    Pill("Y", editor.format.label, primary = false) { command(Command.Y) }
                }
                Text("Code", color = Miel.ink, size = Miel.textSmall)
                BasicTextField(
                    editor.body,
                    Modifier.fillMaxWidth().codeField().focusRequester(codeFocus),
                    textStyle =
                        TextStyle(
                            color = Miel.ink,
                            fontSize = Miel.textSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                    lineLimits =
                        TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 8),
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                "Curated widescreen fixes · Changes require Start fresh",
                color = Miel.muted,
                size = Miel.textSmall,
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(Miel.spaceSm),
            ) {
                itemsIndexed(editor.codes) { index, code ->
                    MoreRow(
                        "${code.name} · ${if (code.enabled) "On" else "Off"}",
                        index == editor.selected,
                        false,
                    ) {
                        if (!editor.busy) command(Command.Pick(index))
                    }
                }
                if (editor.codes.isEmpty())
                    item {
                        Text(
                            if (editor.busy) "Loading…"
                            else "No curated fix for this disc revision. Add a code manually.",
                            color = Miel.muted,
                            size = Miel.textBody,
                        )
                    }
            }
            editor.codes.getOrNull(editor.selected)?.let { code ->
                Text(
                    code.notes.ifEmpty { code.format.label },
                    color = Miel.muted,
                    size = Miel.textSmall,
                    modifier = Modifier.heightIn(max = 80.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
        if (editor.busy || editor.status.isNotEmpty())
            Text(
                if (editor.busy) "Working…" else editor.status,
                color = Miel.muted,
                size = Miel.textSmall,
            )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Miel.spaceSm)) {
            Pill("B", "Back", primary = false) { command(Command.B) }
            if (editor.editing) {
                if (editor.canDelete) Pill("X", "Delete", destructive = true) { command(Command.X) }
                Pill("A", "Save") { command(Command.A) }
            } else {
                Pill("X", "Add", primary = false) { command(Command.X) }
                if (editor.codes.isNotEmpty()) {
                    if (editor.codes[editor.selected].widescreen.not())
                        Pill("R", "Edit", primary = false) { command(Command.NextTab) }
                    Pill("A", "Toggle") { command(Command.A) }
                }
            }
        }
    }
}

private fun Modifier.codeField() =
    relief(RoundedCornerShape(Miel.inset), Miel.surface, inset = true).padding(Miel.inset)
