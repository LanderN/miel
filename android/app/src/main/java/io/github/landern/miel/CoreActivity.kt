package io.github.landern.miel

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject

/**
 * Runs a game in the :azahar process, away from the library's process. The activity shows the top
 * screen and takes input; the bottom screen is a Presentation, so AYN's firmware does not apply its
 * separate bottom-screen volume to the game's audio. Home, Back and pause checkpoint the game;
 * leaving reopens the library through LaunchActivity.
 */
class CoreActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rumble: CoreRumble
    private val staging = Executors.newSingleThreadExecutor()
    private val displayListener = Displays.Listener(this, ::attachLower)
    private var lower: Presentation? = null
    private lateinit var lowerRoot: FrameLayout

    /** The raw core message, shown instead of the overlay in diagnostic runs. */
    private lateinit var status: TextView

    private lateinit var overlay: LoadingOverlay
    private var diagnostic = false
    private var probe = false
    private var invalidRom = false
    private var reportTest = false
    private var debugReports = false
    private var started = false
    private var resumed = false
    private var closing = false
    private var failureShown = false
    private var saveFailureShown = false
    private var resumeAfterSave = false
    @Volatile private var destroyed = false
    @Volatile private var stagingFailed = false
    @Volatile private var stagingProgress = LoadingOverlay.PULSE
    @Volatile private var stagingStatus = "Preparing your game…"
    private var statePath = ""
    private var savePath = ""
    private var lastStatus = ""
    private lateinit var playControls: PlayControls
    private lateinit var lowerDimmer: View
    private var lowerDimmed = false
    private var lowerTouched = false
    private var lowerInteractionUntil = 0L
    private var discCount = 0
    private var discIndex = 0
    private var analogEnabled = true

    /** The game's core; the diagnostic test core renders Azahar's layout. */
    private var core = Core.AZAHAR

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        owner = this
        rumble = CoreRumble(this)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        diagnostic = debuggable && intent.getBooleanExtra(EXTRA_HOST_TEST, false)
        probe = debuggable && intent.getBooleanExtra(EXTRA_PROBE, false)
        invalidRom = probe && intent.getBooleanExtra(EXTRA_INVALID_ROM, false)
        reportTest = debuggable && intent.getBooleanExtra(EXTRA_REPORT_TEST, false)
        intent.getStringExtra(EXTRA_CORE)?.let { name ->
            Core.entries.firstOrNull { it.name == name }?.let { core = it }
        }
        analogEnabled =
            getSharedPreferences("controllers", MODE_PRIVATE)
                .getBoolean(intent.getStringExtra(EXTRA_ROM_URI).orEmpty(), true)
        debugReports = debuggable && intent.getBooleanExtra(EXTRA_DEBUG_REPORTS, false)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        Displays.immersive(window)
        volumeControlStream = AudioManager.STREAM_MUSIC
        lowerRoot =
            object : FrameLayout(this) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    lowerTouched =
                        event.actionMasked != MotionEvent.ACTION_UP &&
                            event.actionMasked != MotionEvent.ACTION_CANCEL
                    wakeLower()
                    return super.dispatchTouchEvent(event)
                }
            }
        lowerRoot.setBackgroundColor(Color.BLACK)
        lowerRoot.addView(screen(0), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        status.setBackgroundColor(0x993f3024.toInt())
        status.setPadding(12, 8, 12, 8)
        overlay = LoadingOverlay(this, intent.getStringExtra(EXTRA_TITLE))
        if (diagnostic) {
            val line = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            line.gravity = Gravity.TOP
            lowerRoot.addView(status, line)
        } else {
            lowerRoot.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        playControls =
            PlayControls(
                this,
                opened = {
                    wakeLower()
                    CoreHost.pause(true)
                },
                dismissed = {
                    wakeLower()
                    if (resumed && !closing && !saveFailureShown) CoreHost.pause(false)
                },
                insert = { CoreHost.changeDisc(it) },
                toggleAnalog = {
                    analogEnabled = !analogEnabled
                    getSharedPreferences("controllers", MODE_PRIVATE)
                        .edit()
                        .putBoolean(intent.getStringExtra(EXTRA_ROM_URI).orEmpty(), analogEnabled)
                        .apply()
                    wakeLower()
                    CoreHost.analog(analogEnabled)
                },
            )
        lowerRoot.addView(playControls, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        lowerDimmer =
            View(this).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        lowerRoot.addView(lowerDimmer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val content = FrameLayout(this)
        content.setBackgroundColor(Color.BLACK)
        content.addView(screen(1), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(content)
        displayListener.register()
        attachLower()
        val art = intent.getStringExtra(EXTRA_ART)
        if (!diagnostic && !art.isNullOrEmpty()) showArt(art)
        when {
            probe -> prepareProbe()
            diagnostic -> prepareTest()
            else -> prepareGame()
        }
        main.post(poll)
    }

    /** A surface for the lower (0) or upper (1) screen. Touches on the lower one reach the core. */
    private fun screen(slot: Int): SurfaceView {
        val view = SurfaceView(this)
        view.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (owner === this@CoreActivity) CoreHost.surface(slot, holder.surface)
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (owner === this@CoreActivity) CoreHost.surface(slot, holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (owner === this@CoreActivity) CoreHost.surface(slot, null)
                }
            },
        )
        if (slot == 0) view.setOnTouchListener(::touch)
        return view
    }

    /**
     * Maps a touch to the core's frame. The surface shows the 4:3 bottom screen, letterboxed, which
     * spans the frame's lower half between the core's side margins.
     */
    private fun touch(view: View, event: MotionEvent): Boolean {
        if (!core.dual) return true
        val aspect = 4f / 3f
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val drawWidth = minOf(width, height * aspect)
        val drawHeight = drawWidth / aspect
        val u = (event.x - (width - drawWidth) / 2) / drawWidth
        val v = (event.y - (height - drawHeight) / 2) / drawHeight
        val action = event.actionMasked
        val down =
            action != MotionEvent.ACTION_UP &&
                action != MotionEvent.ACTION_CANCEL &&
                u in 0f..1f &&
                v in 0f..1f
        val x = core.lowerInset + u * (1 - 2 * core.lowerInset)
        val y = .5f + v * .5f
        CoreHost.touch(x * 2 - 1, y * 2 - 1, down)
        return true
    }

    private fun attachLower() {
        val target = Displays.other(this, Displays.own(this))
        lower?.let {
            if (target != null && it.display.displayId == target.displayId && it.isShowing) return
            it.dismiss()
        }
        lower = null
        (lowerRoot.parent as ViewGroup?)?.removeView(lowerRoot)
        if (target == null) return
        try {
            val presentation = Presentation(this, target)
            @Suppress("DEPRECATION")
            presentation.window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            presentation.setContentView(lowerRoot)
            presentation.window?.let(Displays::immersive)
            lower = presentation
            presentation.show()
        } catch (error: RuntimeException) {
            Log.e(Displays.TAG, "Lower game screen failed", error)
            lower = null
        }
    }

    private fun showArt(path: String) {
        val metrics = resources.displayMetrics
        val size = maxOf(metrics.widthPixels, metrics.heightPixels)
        staging.execute {
            val bitmap = HeroView.decode(path, size, size) ?: return@execute
            main.post { if (!destroyed) overlay.art(bitmap) }
        }
    }

    private fun prepareTest() {
        val root = coreDirectory("core-host-test")
        val hardware = intent.getBooleanExtra(EXTRA_HARDWARE_TEST, false)
        val states = File(root, "states")
        states.mkdirs()
        val name = if (hardware) "diagnostic-hw" else "diagnostic"
        statePath = File(states, if (hardware) "test-hw.state" else "test.state").absolutePath
        savePath =
            File(root, "saves/$TEST_CORE_NAME").apply { mkdirs() }.absolutePath + "/$name.srm"
        start(nativeLibrary(TEST_CORE), "$name.test", root)
    }

    private fun prepareProbe() {
        val root = coreDirectory("core-host-probe")
        var rom = ""
        if (invalidRom) {
            val file = File(root, "synthetic-invalid.3ds")
            try {
                file.writeText("SYNTHETIC INVALID ROM TEST. Not game content.")
            } catch (error: IOException) {
                fail(error.toString())
                return
            }
            rom = file.absolutePath
        }
        CoreHost.start(
            nativeLibrary(Core.AZAHAR.library),
            rom,
            root.absolutePath,
            File(root, "system").absolutePath,
            File(root, "saves").absolutePath,
            "",
            "",
            Core.AZAHAR.coreName,
            Core.AZAHAR.options(CoreSettings()),
            Core.AZAHAR.dual,
            Core.AZAHAR.lowerInset,
            false,
            !invalidRom,
            false,
        )
        started = true
    }

    private fun prepareGame() {
        if (!File(nativeLibrary(core.library)).isFile) {
            fail(
                "${core.coreName} is packaged for ARM64 Thor hardware. This device needs an ARM64 build.",
            )
            return
        }
        val uri = intent.getStringExtra(EXTRA_ROM_URI)
        val filename = intent.getStringExtra(EXTRA_FILENAME)
        val remote = uri?.startsWith(RomM.URI) == true
        if (uri == null || filename == null || !(remote || uri.startsWith("content://"))) {
            fail("No readable ROM was selected.")
            return
        }
        staging.execute {
            try {
                val source = if (remote) Uri.fromFile(download(filename)) else Uri.parse(uri)
                stagingStatus = "Preparing your game…"
                val rom =
                    if (
                        core == Core.PCSX &&
                            filename.substringAfterLast('.').lowercase() in
                                setOf("cue", "m3u", "zip")
                    ) {
                        DiscCopy.make(
                            this,
                            source,
                            filename,
                            intent
                                .getStringExtra(EXTRA_ROM_DIRECTORY)
                                ?.takeIf { it.isNotEmpty() }
                                ?.let(Uri::parse),
                        ) {
                            stagingProgress = it
                        }
                    } else
                        RomCopy.make(this, source, filename, core.extensions) {
                            stagingProgress = it
                        }
                val root = SaveStorage.root()
                CoreAssets.system(this, core)
                val states = File(root, "states/${core.version}")
                if (!states.isDirectory && !states.mkdirs()) {
                    throw IOException("Cannot create $states")
                }
                statePath = File(states, rom.sha256 + ".state").absolutePath
                savePath = SaveStorage.save(core, filename).absolutePath
                main.post {
                    if (!destroyed) start(nativeLibrary(core.library), rom.file.absolutePath, root)
                }
            } catch (error: Exception) {
                fail("Cannot open game: " + error.message)
            }
        }
    }

    /** The RomM game in `filename`, downloaded to the miel folder the first time. */
    private fun download(filename: String): File {
        val file = SaveStorage.download(intent.getStringExtra(EXTRA_PLATFORM).orEmpty(), filename)
        if (!file.isFile) {
            stagingStatus = "Downloading your game…"
            RomM.download(
                intent.getStringExtra(EXTRA_ROMM_LINK).orEmpty(),
                intent.getStringExtra(EXTRA_ROMM_TOKEN).orEmpty(),
                file,
            ) {
                stagingProgress = it
            }
        }
        return file
    }

    private fun fail(message: String) {
        stagingStatus = message
        stagingFailed = true
    }

    private fun nativeLibrary(name: String) =
        File(applicationInfo.nativeLibraryDir, name).absolutePath

    /** A private core directory with the system and saves folders it expects. */
    private fun coreDirectory(name: String): File {
        val root = File(filesDir, name)
        File(root, "system").mkdirs()
        File(root, "saves").mkdirs()
        return root
    }

    /** Starts the core once the previous session in this process has stopped. */
    private fun start(library: String, rom: String, root: File) {
        if (destroyed || closing || owner !== this) return
        try {
            if (JSONObject(CoreHost.report()).optBoolean("running")) {
                CoreHost.stop()
                stagingStatus = "Closing previous session…"
                stagingProgress = LoadingOverlay.PULSE
                main.postDelayed({ start(library, rom, root) }, 100)
                return
            }
        } catch (ignored: JSONException) {}
        if (!diagnostic) RomCopy.deleteOthers(File(rom))
        val settings =
            CoreSettings(
                intent.getIntExtra(EXTRA_RESOLUTION, 3),
                intent.getBooleanExtra(EXTRA_HARDWARE_SHADERS, true),
            )
        CoreHost.start(
            library,
            rom,
            root.absolutePath,
            if (diagnostic) File(root, "system").absolutePath
            else CoreAssets.system(this, core).absolutePath,
            if (!diagnostic && core == Core.PPSSPP)
                SaveStorage.memoryStick(intent.getStringExtra(EXTRA_FILENAME).orEmpty())
                    .absolutePath
            else File(root, "saves").absolutePath,
            statePath,
            savePath,
            if (diagnostic) TEST_CORE_NAME else core.coreName,
            if (diagnostic) emptyArray()
            else intent.getStringArrayExtra(EXTRA_OPTIONS) ?: core.options(settings),
            core.dual,
            core.lowerInset,
            diagnostic,
            false,
            intent.getBooleanExtra(EXTRA_FRESH, false),
        )
        started = true
        if (core == Core.PCSX) CoreHost.analog(analogEnabled)
        CoreHost.pause(!resumed)
    }

    /** Follows the core's report four times a second until this activity finishes. */
    private val poll: Runnable =
        object : Runnable {
            override fun run() {
                if (destroyed || owner !== this@CoreActivity) return
                try {
                    if (react(report())) return
                } catch (error: Exception) {
                    val text = "Core status unavailable: " + error.message
                    status.text = text
                    overlay.update(text, LoadingOverlay.NONE)
                }
                main.postDelayed(this, 250)
            }
        }

    private fun report(): JSONObject =
        if (started) {
            JSONObject(CoreHost.report())
        } else {
            JSONObject().put("message", stagingStatus).put("running", false)
        }

    /** Updates the screens and dialogs for `info`. Returns true once the activity finishes. */
    private fun react(info: JSONObject): Boolean {
        val running = info.optBoolean("running")
        showStatus(info)
        dimLower(
            !diagnostic &&
                !core.dual &&
                info.optBoolean("ready") &&
                running &&
                !info.optBoolean("paused") &&
                !closing &&
                !playControls.isOpen &&
                !lowerTouched &&
                SystemClock.uptimeMillis() >= lowerInteractionUntil,
        )
        discCount = info.optInt("discCount")
        discIndex = info.optInt("discIndex")
        playControls.update(
            discCount,
            discIndex,
            info.optBoolean("ready") && !closing,
            info.optBoolean("discFailed"),
            core == Core.PCSX && !diagnostic,
            analogEnabled,
        )
        if (info.optBoolean("checkpointFailed")) {
            if (running && !saveFailureShown) showSaveFailure()
        } else {
            saveFailureShown = false
            if (resumeAfterSave) {
                resumeAfterSave = false
                if (resumed && !closing && !playControls.isOpen) CoreHost.pause(false)
            }
        }
        if (diagnostic || probe || reportTest || debugReports) writeReport(info)
        if (started && !running && (probe || reportTest)) {
            if (probe) setResult(if (info.optBoolean("failed")) RESULT_CANCELED else RESULT_OK)
            finish()
            return true
        }
        if (!probe && started && !running && info.optBoolean("failed") && !failureShown) {
            failureShown = true
            AlertDialog.Builder(this)
                .setTitle(
                    if (info.optBoolean("restoreBlocked")) {
                        "Couldn’t resume your game"
                    } else {
                        "Game could not start"
                    },
                )
                .setMessage(info.optString("message"))
                .setPositiveButton("Return to library") { _, _ ->
                    closing = true
                    finishReturn()
                }
                .show()
        }
        if (started && !running && info.optBoolean("exitReady")) {
            setResult(RESULT_OK)
            finishReturn()
            return true
        }
        return false
    }

    private fun showStatus(info: JSONObject) {
        val text =
            when {
                diagnostic -> "HOST TEST · " + info.optString("message")
                closing -> KEEPING
                info.optBoolean("ready") -> ""
                started -> "Getting your game ready…"
                else -> stagingStatus
            }
        if (text != lastStatus) {
            status.text = text
            lastStatus = text
        }
        status.visibility = if (text.isEmpty()) View.INVISIBLE else View.VISIBLE
        val progress =
            when {
                closing || started -> LoadingOverlay.PULSE
                stagingFailed -> LoadingOverlay.NONE
                else -> stagingProgress
            }
        overlay.update(text, progress)
    }

    /** Keeps the game paused until a retry saves it, or the player chooses to play on. */
    private fun showSaveFailure() {
        val returnAfterRetry = closing
        closing = false
        saveFailureShown = true
        CoreHost.pause(true)
        AlertDialog.Builder(this)
            .setTitle("Couldn’t keep your place")
            .setMessage(
                "Your game is paused. Check that there is free storage, then try again." +
                    " Keep this game open until your place can be saved.",
            )
            .setPositiveButton("Try again") { _, _ ->
                if (returnAfterRetry) {
                    returnToLibrary()
                } else {
                    resumeAfterSave = true
                    CoreHost.save(false)
                }
            }
            .setNegativeButton("Keep playing") { _, _ ->
                saveFailureShown = false
                if (!playControls.isOpen) CoreHost.pause(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun wakeLower() {
        lowerInteractionUntil = SystemClock.uptimeMillis() + 8_000
        dimLower(false)
    }

    private fun dimLower(dim: Boolean) {
        if (!::lowerDimmer.isInitialized || dim == lowerDimmed) return
        lowerDimmed = dim
        lowerDimmer.animate().cancel()
        if (dim) lowerDimmer.animate().alpha(.85f).setDuration(250).start()
        else lowerDimmer.alpha = 0f
    }

    private fun writeReport(info: JSONObject) {
        info.put("lowerDimmed", lowerDimmed)
        info.put("statePath", statePath)
        info.put("pid", Process.myPid())
        info.put("testRun", intent.getStringExtra(EXTRA_TEST_RUN))
        val file = AtomicFile(File(filesDir, REPORT_FILE))
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(info.toString().toByteArray())
            file.finishWrite(output)
        } catch (error: IOException) {
            if (output != null) file.failWrite(output)
        }
    }

    private fun screenSleeping() =
        getSystemService(PowerManager::class.java)?.isInteractive == false

    /**
     * Leaving the foreground returns to the library. Screen sleep only checkpoints, keeping the
     * core loaded so waking continues the same session.
     */
    private val backgroundExit = Runnable {
        if (!destroyed && !closing && owner === this && !screenSleeping()) returnToLibrary()
    }

    private fun pauseForLifecycle() {
        if (probe || closing || destroyed || owner !== this) return
        if (started) {
            CoreHost.pause(true)
            CoreHost.save(false)
        }
        // Power state can lag the pause callback during lid close, so the checkpoint starts now
        // and the decision between sleep and leaving waits.
        main.removeCallbacks(backgroundExit)
        main.postDelayed(backgroundExit, 300)
    }

    private fun resumeSession() {
        main.removeCallbacks(backgroundExit)
        if (started && !closing && !saveFailureShown && !screenSleeping() && !playControls.isOpen)
            CoreHost.pause(false)
    }

    /** Checkpoints and exits the core; the report loop finishes once it is written. */
    private fun returnToLibrary() {
        if (closing || destroyed || owner !== this) return
        closing = true
        dimLower(false)
        if (!started || !coreRunning()) {
            finishReturn()
            return
        }
        CoreHost.pause(true)
        CoreHost.save(true)
        status.text = KEEPING
        overlay.update(KEEPING, LoadingOverlay.PULSE)
    }

    /** The library ends its process while a game runs, so every return reopens it. */
    private fun finishReturn() {
        finish()
        if (!diagnostic && !probe && !reportTest) {
            startActivity(
                Intent(this, LaunchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(LaunchActivity.AFTER_GAME, true),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == RETURN_HOME) returnToLibrary()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playControls.isOpen) playControls.close() else returnToLibrary()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        rumble.start()
        resumeSession()
    }

    override fun onPause() {
        resumed = false
        rumble.stop()
        pauseForLifecycle()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        rumble.stop()
        main.removeCallbacks(poll)
        staging.shutdownNow()
        if (owner === this) {
            owner = null
            if (started) CoreHost.stop()
        }
        lower?.dismiss()
        displayListener.unregister()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (MediaVolume.key(this, event)) return true
        if (playControls.key(event)) return true
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BUTTON_THUMBR && discCount > 1) {
            if (
                event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount == 0 &&
                    !closing &&
                    !saveFailureShown
            )
                playControls.open()
            return true
        }
        if (key == KeyEvent.KEYCODE_BUTTON_MODE || key == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) returnToLibrary()
            return true
        }
        val button = button(key)
        if (button >= 0) rumble.select(event.device)
        if (closing || saveFailureShown || failureShown || button < 0) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            CoreHost.button(button, event.action == KeyEvent.ACTION_DOWN)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (playControls.isOpen) return true
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(event)
        }
        rumble.select(event.device)
        CoreHost.axes(
            event.getAxisValue(MotionEvent.AXIS_X),
            event.getAxisValue(MotionEvent.AXIS_Y),
            event.getAxisValue(MotionEvent.AXIS_Z),
            event.getAxisValue(MotionEvent.AXIS_RZ),
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
            ),
            maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            ),
        )
        return true
    }

    companion object {
        /** Brings a running game back to the library after it checkpoints. */
        const val RETURN_HOME = "io.github.landern.miel.RETURN_HOME"

        /** The game's Core, by name; Azahar without it. */
        const val EXTRA_CORE = "core"
        const val EXTRA_ROM_URI = "romUri"

        /** The game's platform, which names the folder a RomM download goes to. */
        const val EXTRA_PLATFORM = "platform"

        /** Where a game only on RomM downloads from, and the device token that allows it. */
        const val EXTRA_ROMM_LINK = "rommLink"
        const val EXTRA_ROMM_TOKEN = "rommToken"
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_ROM_DIRECTORY = "romDirectory"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ART = "art"
        const val EXTRA_FRESH = "fresh"
        const val EXTRA_OPTIONS = "coreOptions"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_HARDWARE_SHADERS = "hardwareShaders"

        /** Writes REPORT_FILE while a game runs. */
        const val EXTRA_DEBUG_REPORTS = "debugReports"

        // Test modes, which debuggable builds accept; see CoreHostInstrumentation.
        /** Runs the diagnostic test core instead of a game. */
        const val EXTRA_HOST_TEST = "hostTest"

        /** Renders the diagnostic core with GLES rather than software frames. */
        const val EXTRA_HARDWARE_TEST = "hardwareTest"

        /**
         * Initialises Azahar without a game, or with EXTRA_INVALID_ROM a synthetic invalid file.
         */
        const val EXTRA_PROBE = "azaharProbe"

        const val EXTRA_INVALID_ROM = "invalidRomTest"

        /** Stages a real document like a game, writes reports and finishes once the core stops. */
        const val EXTRA_REPORT_TEST = "reportTest"

        /** Copied into each report, so a test can tell its runs apart. */
        const val EXTRA_TEST_RUN = "testRun"

        const val REPORT_FILE = "core-host-report.json"

        private const val TEST_CORE = "libmiel_testcore.so"
        private const val TEST_CORE_NAME = "miel test core"
        private const val KEEPING = "Keeping your place…"

        /**
         * The newest instance. Callbacks from an older one, still finishing during a fast return
         * and relaunch, must not stop or detach the next session.
         */
        private var owner: CoreActivity? = null

        /** The libretro button for an Android key, or -1. */
        private fun button(key: Int): Int =
            when (key) {
                KeyEvent.KEYCODE_BUTTON_A -> CoreHost.A
                KeyEvent.KEYCODE_BUTTON_B -> CoreHost.B
                KeyEvent.KEYCODE_BUTTON_X -> CoreHost.X
                KeyEvent.KEYCODE_BUTTON_Y -> CoreHost.Y
                KeyEvent.KEYCODE_DPAD_UP -> CoreHost.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> CoreHost.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> CoreHost.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> CoreHost.RIGHT
                KeyEvent.KEYCODE_BUTTON_L1 -> CoreHost.L
                KeyEvent.KEYCODE_BUTTON_R1 -> CoreHost.R
                KeyEvent.KEYCODE_BUTTON_L2 -> CoreHost.L2
                KeyEvent.KEYCODE_BUTTON_R2 -> CoreHost.R2
                KeyEvent.KEYCODE_BUTTON_THUMBL -> CoreHost.L3
                KeyEvent.KEYCODE_BUTTON_THUMBR -> CoreHost.R3
                KeyEvent.KEYCODE_BUTTON_START -> CoreHost.START
                KeyEvent.KEYCODE_BUTTON_SELECT -> CoreHost.SELECT
                else -> -1
            }

        /** Whether the core reports a running session; true when its report is unreadable. */
        private fun coreRunning(): Boolean =
            try {
                JSONObject(CoreHost.report()).optBoolean("running")
            } catch (error: JSONException) {
                true
            }
    }
}
