package io.github.landern.miel

import android.app.Activity
import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONException
import org.json.JSONObject

/**
 * Checks CoreActivity and the native host through CoreActivity's report file. The default mode runs
 * the diagnostic test core through rendering, input, sleep, exits, process death and checkpoint
 * recovery; it never runs a game. The probe modes run the real Azahar core without a game. Run with
 * scripts/test-core-host.sh.
 */
class CoreHostInstrumentation : Instrumentation() {
    private var probe = false
    private var invalid = false
    private var hardware = false
    private var stageInvalid = false
    private var romUri: String? = null
    private var filename: String? = null
    private var runId = ""
    private lateinit var files: File
    private lateinit var checkpoint: File
    private lateinit var backup: File
    private lateinit var report: File

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        val arguments = args ?: Bundle()
        val mode = arguments.getString("mode", "")
        probe = mode == "probe" || mode == "invalid"
        invalid = mode == "invalid"
        stageInvalid = mode == "stage-invalid"
        hardware = arguments.getString("renderer") == "hardware"
        romUri = arguments.getString("romUri")
        filename = arguments.getString("filename")
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            files = targetContext.filesDir
            report = File(files, CoreActivity.REPORT_FILE)
            val states = File(files, "core-host-test/states")
            states.mkdirs()
            checkpoint = File(states, if (hardware) "test-hw.state" else "test.state")
            backup = File("$checkpoint.bak")
            val summary = if (probe || stageInvalid) probeAzahar() else runDiagnostic()
            result.putString("stream", "PASS: $summary\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: $error\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun probeAzahar(): String {
        launch(false)
        val info = await { !it.optBoolean("running") && it.has("core") }
        check(info.optString("core") == "Azahar", "Genuine Azahar identity not reported")
        if (invalid || stageInvalid) {
            check(
                info.optBoolean("failed") && info.optLong("frames") == 0L,
                "Synthetic invalid file was not rejected",
            )
        } else {
            check(
                !info.optBoolean("failed") && "initialized" in info.optString("message"),
                "Azahar initialization failed",
            )
        }
        val checked =
            when {
                stageInvalid -> "SAF staging and invalid-file rejection"
                invalid -> "invalid-file rejection"
                else -> "initialization without a ROM"
            }
        return "genuine Android Azahar $checked. $info"
    }

    private fun runDiagnostic(): String {
        checkRomCopies()
        if (homeRoleHeld()) {
            // Instrumentation restarts the app's process. Let the default Home open its library
            // before a core activity starts above it.
            targetContext.startActivity(
                Intent(targetContext, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Thread.sleep(1500)
            waitForIdleSync()
        }
        for (suffix in listOf("", ".bak", ".tmp", ".bak.tmp")) File("$checkpoint$suffix").delete()
        val inGameSave =
            File(
                files,
                "core-host-test/saves/miel test core/" +
                    (if (hardware) "diagnostic-hw" else "diagnostic") +
                    ".srm",
            )
        inGameSave.delete()

        val periodic = renderAndCheckpoint()
        sleepKeepsTheSession(periodic)
        val home = homeAndBackSave()
        val recovered = coreDeathKeepsTheCheckpoint()
        corruptPrimaryFallsBackToBackup()
        failedWritePausesUntilRetry()
        val mismatch = incompatibleCheckpointBlocksPlay()
        startFreshKeepsInGameSaves(inGameSave)

        return (if (hardware) "GLES hardware" else "software-upload") +
            " diagnostic: ROM and ZIP staging; dual screens/audio/input; screen sleep checkpoints in place and" +
            " wakes both screens; periodic checkpoints; Back and OS Home save-before-return;" +
            " early return during startup; killed-core recovery; corrupt-primary fallback" +
            " without backup damage; failed-write pause/retry; incompatible resume blocked" +
            " unchanged; explicit fresh start loads and keeps in-game saves.\nPeriodic: " +
            periodic +
            "\nHome: " +
            home +
            "\nKilled-core recovery: " +
            recovered +
            "\nBlocked resume: " +
            mismatch
    }

    private fun checkRomCopies() {
        val source = File.createTempFile("rom-copy-", ".3ds", targetContext.cacheDir)
        val archive = File("$source.zip")
        val data = ByteArray(33 shl 20) { it.toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(data).toHex()
        try {
            source.writeBytes(data)
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry(source.name))
                it.write(data)
                it.closeEntry()
            }
            for (input in listOf(source, archive)) {
                val copy =
                    RomCopy.make(targetContext, Uri.fromFile(input), input.name, setOf("3ds")) {}
                try {
                    check(copy.sha256 == hash, "ROM hash changed during staging")
                    check(copy.file.readBytes().contentEquals(data), "Staged ROM content changed")
                } finally {
                    copy.file.delete()
                }
            }
            check(source.readBytes().contentEquals(data), "Source ROM content changed")
        } finally {
            source.delete()
            archive.delete()
        }
    }

    private fun renderAndCheckpoint(): JSONObject {
        launch(false)
        await { it.optBoolean("ready") }
        Thread.sleep(300)
        injectInput()
        val periodic = await { it.optLong("periodicSaves") >= 1 }
        check(
            periodic.optLong("lowerFrames") > 0 && periodic.optLong("upperFrames") > 0,
            "Both screens must render",
        )
        check(
            periodic.optLong("audioFrames") > 0 &&
                periodic.optBoolean("audioOutput") &&
                periodic.optLong("audioDelivered") > 0,
            "Android audio must consume PCM",
        )
        check(
            periodic.optLong("glErrors") == 0L && periodic.optBoolean("hardware") == hardware,
            "Graphics context or blit error",
        )
        check(periodic.optLong("inputEvents") >= 4, "Controller/touch input did not reach host")
        return periodic
    }

    /** Screen sleep checkpoints and keeps the core loaded, unlike OS Home; Back then saves. */
    private fun sleepKeepsTheSession(periodic: JSONObject) {
        val asleep: JSONObject
        try {
            shell("input keyevent 223")
            await {
                it.optBoolean("paused") &&
                    it.optLong("checkpoints") > periodic.optLong("checkpoints")
            }
            Thread.sleep(800)
            asleep = await { it.optBoolean("paused") }
            check(
                asleep.optBoolean("running") &&
                    !asleep.optBoolean("exitReady") &&
                    asleep.optInt("pid") == periodic.optInt("pid"),
                "Screen sleep unloaded the core",
            )
        } finally {
            shell("input keyevent 224")
        }
        val awake = await {
            !it.optBoolean("paused") &&
                it.optBoolean("dual") &&
                it.optLong("upperFrames") > asleep.optLong("upperFrames") &&
                it.optLong("lowerFrames") > asleep.optLong("lowerFrames")
        }
        check(
            awake.optInt("pid") == periodic.optInt("pid") && !awake.optBoolean("exitReady"),
            "Wake restarted or exited the session",
        )
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        check(awaitExit().optBoolean("saved"), "Back did not save before returning")
    }

    /** Resume restores; OS Home saves and finishes; Back during startup keeps the old session. */
    private fun homeAndBackSave(): JSONObject {
        launch(false)
        check(await { it.optBoolean("ready") }.optBoolean("restored"), "Automatic resume failed")
        shell(HOME_KEY)
        val home = awaitExit()
        check(home.optBoolean("saved"), "OS Home did not checkpoint")

        launch(false)
        await { it.optBoolean("running") }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        val early = awaitExit()
        check(
            early.optBoolean("restored") && early.optBoolean("saved"),
            "Early return overwrote or skipped the prior session",
        )
        return home
    }

    /** Kills only the core's process, without a pause callback, and resumes its checkpoint. */
    private fun coreDeathKeepsTheCheckpoint(): JSONObject {
        launch(false)
        val beforeKill = await { it.optLong("periodicSaves") >= 1 }
        val durable = checkpoint.readBytes()
        val killedPid = beforeKill.getInt("pid")
        check(killedPid != Process.myPid(), "Core process must be isolated")
        Process.killProcess(killedPid)
        Thread.sleep(800)
        check(
            durable.contentEquals(checkpoint.readBytes()),
            "Killing the core altered its durable checkpoint",
        )
        launch(false)
        val recovered = await { it.optBoolean("ready") }
        check(recovered.optBoolean("restored"), "Process death did not recover periodic state")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitExit()
        return recovered
    }

    /** The first write after restoring the backup must not replace that backup. */
    private fun corruptPrimaryFallsBackToBackup() {
        val good = backup.readBytes()
        val corrupt = checkpoint.readBytes()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        checkpoint.writeBytes(corrupt)
        launch(false)
        val fallback = await { it.optLong("periodicSaves") >= 1 }
        check(fallback.optBoolean("restoredBackup"), "Corrupt primary did not restore backup")
        check(good.contentEquals(backup.readBytes()), "Corrupt primary replaced the good backup")
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitExit()
    }

    /**
     * A failed write pauses the game and keeps both files until Try again succeeds. When this app
     * is the default Home, OS Home also waits for the retry.
     */
    private fun failedWritePausesUntilRetry() {
        val primaryBefore = checkpoint.readBytes()
        val backupBefore = backup.readBytes()
        val blocker = File("$checkpoint.tmp")
        check(blocker.mkdir(), "Cannot install write-failure fixture")
        launch(false)
        val blocked = await { it.optBoolean("checkpointFailed") }
        check(
            blocked.optBoolean("paused") && blocked.optBoolean("running"),
            "Save failure must retain a paused session",
        )
        check(
            primaryBefore.contentEquals(checkpoint.readBytes()) &&
                backupBefore.contentEquals(backup.readBytes()),
            "Write failure damaged recovery files",
        )
        blocker.delete()
        clickText("Try again")
        await {
            it.optLong("checkpoints") >= 1 &&
                !it.optBoolean("checkpointFailed") &&
                !it.optBoolean("paused")
        }
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitExit()

        if (!homeRoleHeld()) return
        launch(false)
        await { it.optBoolean("ready") }
        check(blocker.mkdir(), "Cannot install Home write-failure fixture")
        shell(HOME_KEY)
        val homeBlocked = await { it.optBoolean("checkpointFailed") }
        check(
            homeBlocked.optBoolean("running") && homeBlocked.optBoolean("paused"),
            "Home removed the core before a failed checkpoint could be retried",
        )
        blocker.delete()
        clickText("Try again")
        check(awaitExit().optBoolean("saved"), "Home retry did not finish its durable checkpoint")
    }

    /** A checkpoint from another core build blocks play and stays unchanged. */
    private fun incompatibleCheckpointBlocksPlay(): JSONObject {
        for (file in listOf(checkpoint, backup)) {
            val state = file.readBytes()
            // The core identity follows the 8-byte magic and its little-endian length.
            val length =
                (state[8].toInt() and 255) or
                    ((state[9].toInt() and 255) shl 8) or
                    ((state[10].toInt() and 255) shl 16) or
                    ((state[11].toInt() and 255) shl 24)
            state[12 + length - 1] = '9'.code.toByte()
            file.writeBytes(state)
        }
        val before = checkpoint.readBytes()
        launch(false)
        val mismatch = await { it.optBoolean("failed") && !it.optBoolean("running") }
        check(
            mismatch.optBoolean("restoreBlocked") && !mismatch.optBoolean("ready"),
            "Incompatible resume must block play",
        )
        check(before.contentEquals(checkpoint.readBytes()), "Failed restore overwrote the session")
        clickText("Return to library")
        Thread.sleep(500)
        return mismatch
    }

    /** The test core's in-game save is its frame number, which it continues from. */
    private fun startFreshKeepsInGameSaves(inGameSave: File) {
        check(inGameSave.length() == 8L, "Checkpoints did not write the in-game save")
        val marker = 5_000_000L
        inGameSave.writeBytes(ByteBuffer.allocate(8).order(LITTLE_ENDIAN).putLong(marker).array())
        launch(true)
        val fresh = await { it.optBoolean("ready") && it.optBoolean("saved") }
        check(!fresh.optBoolean("restored"), "Explicit fresh start restored old state")
        check(
            ByteBuffer.wrap(inGameSave.readBytes()).order(LITTLE_ENDIAN).long >= marker,
            "Fresh start did not continue from the in-game save",
        )
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitExit()
    }

    private fun homeRoleHeld(): Boolean =
        targetContext
            .getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_HOME) == true

    private fun launch(fresh: Boolean) {
        report.delete()
        runId = UUID.randomUUID().toString()
        targetContext.startActivity(
            Intent(targetContext, CoreActivity::class.java)
                .putExtra(CoreActivity.EXTRA_HOST_TEST, !probe && !stageInvalid)
                .putExtra(CoreActivity.EXTRA_PROBE, probe)
                .putExtra(CoreActivity.EXTRA_INVALID_ROM, invalid)
                .putExtra(CoreActivity.EXTRA_HARDWARE_TEST, hardware)
                .putExtra(CoreActivity.EXTRA_REPORT_TEST, stageInvalid)
                .putExtra(CoreActivity.EXTRA_ROM_URI, romUri)
                .putExtra(CoreActivity.EXTRA_FILENAME, filename)
                .putExtra(CoreActivity.EXTRA_FRESH, fresh)
                .putExtra(CoreActivity.EXTRA_TEST_RUN, runId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Waits up to 30 seconds for this run's report to satisfy `condition`. */
    private fun await(condition: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 30000
        var latest: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            if (report.isFile) {
                try {
                    val info = JSONObject(report.readText())
                    latest = info
                    if (runId == info.optString("testRun") && condition(info)) return info
                } catch (ignored: JSONException) {} catch (ignored: IOException) {}
            }
            Thread.sleep(100)
        }
        throw AssertionError("Host test timed out: $latest")
    }

    private fun awaitExit(): JSONObject {
        val info = await { it.optBoolean("exitReady") && !it.optBoolean("running") }
        Thread.sleep(500)
        return info
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private fun clickText(text: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val root = uiAutomation.rootInActiveWindow
            if (root != null) {
                for (node in root.findAccessibilityNodeInfosByText(text)) {
                    if (
                        text.equals(node.text?.toString(), ignoreCase = true) &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        return
                    }
                }
            }
            Thread.sleep(100)
        }
        throw AssertionError("Missing action: $text")
    }

    /** Presses A and right, taps the lower screen, and moves the stick and left trigger. */
    private fun injectInput() {
        sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A))
        Thread.sleep(100)
        sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A))
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 640f, 400f, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        sendPointerSync(down)
        down.recycle()
        Thread.sleep(100)
        val up =
            MotionEvent.obtain(
                now,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                640f,
                400f,
                0,
            )
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        sendPointerSync(up)
        up.recycle()
        val properties = MotionEvent.PointerProperties().apply { id = 0 }
        val coords =
            MotionEvent.PointerCoords().apply {
                x = .5f
                y = -.25f
                setAxisValue(MotionEvent.AXIS_LTRIGGER, 1f)
            }
        val joystick =
            MotionEvent.obtain(
                now,
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                1,
                arrayOf(properties),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_JOYSTICK,
                0,
            )
        uiAutomation.injectInputEvent(joystick, true)
        joystick.recycle()
    }

    private companion object {
        const val HOME_KEY = "input -d 0 keyevent 3"

        fun check(value: Boolean, message: String) {
            if (!value) throw AssertionError(message)
        }
    }
}
