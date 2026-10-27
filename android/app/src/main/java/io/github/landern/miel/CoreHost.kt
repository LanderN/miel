package io.github.landern.miel

import android.view.Surface

/**
 * The native libretro host in libmiel_core.so. One worker thread makes every core and GL call;
 * these methods queue requests for it and return at once.
 */
object CoreHost {
    // libretro's RETRO_DEVICE_ID_JOYPAD_* button IDs.
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13
    const val L3 = 14
    const val R3 = 15

    init {
        System.loadLibrary("miel_core")
    }

    /** Releases a staged ROM's file-cache pages; returns a POSIX error code, or zero. */
    @JvmStatic external fun discardRomCache(fd: Int, offset: Long, length: Long): Int

    /**
     * Loads the core `library`, which must report `coreName`, with `options` as key and value
     * pairs, and the game `rom`, with runtime files in `directory`, `systemDirectory` and
     * `saveDirectory`. Keeps the core's in-game save at `save` and resumes the checkpoint at
     * `state` unless `fresh`. A `probe` only initialises the core. See Core for `dual` and
     * `lowerInset`.
     */
    @JvmStatic
    external fun start(
        library: String,
        rom: String,
        directory: String,
        systemDirectory: String,
        saveDirectory: String,
        state: String,
        save: String,
        coreName: String,
        options: Array<String>,
        dual: Boolean,
        lowerInset: Float,
        diagnostic: Boolean,
        probe: Boolean,
        fresh: Boolean,
    )

    /** Attaches or (with null) detaches the surface for the lower (0) or upper (1) screen. */
    @JvmStatic external fun surface(slot: Int, surface: Surface?)

    /** Uses DualShock when enabled, or the original digital PlayStation controller. */
    @JvmStatic external fun analog(enabled: Boolean)

    /** Selects a disc from the loaded playlist between core frames. */
    @JvmStatic external fun changeDisc(index: Int)

    /** Runs the game at triple speed without sound. */
    @JvmStatic external fun fastForward(on: Boolean)

    /** The stronger port-zero rumble motor, from 0 to 65535; zero while paused or stopped. */
    @JvmStatic external fun rumble(): Int

    @JvmStatic external fun pause(paused: Boolean)

    /** Requests a checkpoint, then an exit once it is written. */
    @JvmStatic external fun save(exitAfterSave: Boolean)

    @JvmStatic external fun stop()

    @JvmStatic external fun button(id: Int, down: Boolean)

    @JvmStatic
    external fun axes(
        x: Float,
        y: Float,
        z: Float,
        rz: Float,
        hatX: Float,
        hatY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
    )

    /** A touch on the lower screen, in -1..1 coordinates of the core's frame. */
    @JvmStatic external fun touch(x: Float, y: Float, down: Boolean)

    /** The session's state and counters as JSON. */
    @JvmStatic external fun report(): String
}
