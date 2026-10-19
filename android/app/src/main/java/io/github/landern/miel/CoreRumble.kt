package io.github.landern.miel

import android.content.Context
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice

/** Plays core rumble on the active controller, falling back to the handheld's motor. */
class CoreRumble(context: Context) : InputManager.InputDeviceListener {
    private val handler = Handler(Looper.getMainLooper())
    private val inputs = context.getSystemService(InputManager::class.java)
    private val fallback =
        if (Build.VERSION.SDK_INT >= 31)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
    private var vibrator: Vibrator? = null
    private var deviceId = -1
    private var amplitude = 0
    private var refreshed = 0L
    private var active = false

    private val tick =
        object : Runnable {
            override fun run() {
                if (!active) return
                val strength = CoreHost.rumble()
                val next = if (strength == 0) 0 else (strength * 255 / 65535).coerceAtLeast(1)
                val now = SystemClock.uptimeMillis()
                if (next != amplitude || next > 0 && now - refreshed >= 50) {
                    amplitude = next
                    refreshed = now
                    if (next == 0) vibrator?.cancel()
                    else
                        vibrator?.let {
                            val level =
                                if (it.hasAmplitudeControl()) next
                                else VibrationEffect.DEFAULT_AMPLITUDE
                            it.vibrate(VibrationEffect.createOneShot(100, level), attributes)
                        }
                }
                handler.postDelayed(this, 16)
            }
        }

    fun start() {
        if (active) return
        active = true
        inputs.registerInputDeviceListener(this, handler)
        select(null)
        handler.post(tick)
    }

    fun stop() {
        active = false
        inputs.unregisterInputDeviceListener(this)
        handler.removeCallbacks(tick)
        vibrator?.cancel()
        amplitude = 0
    }

    fun select(device: InputDevice?) {
        if (!active) return
        if (device != null && !isController(device)) return
        if (device != null && device.id == deviceId) return
        val controller =
            device
                ?: InputDevice.getDeviceIds()
                    .asSequence()
                    .mapNotNull(InputDevice::getDevice)
                    .firstOrNull {
                        isController(it) && motor(it).hasVibrator()
                    }
        vibrator?.cancel()
        vibrator =
            controller?.let(::motor)?.takeIf { it.hasVibrator() }
                ?: fallback?.takeIf { it.hasVibrator() }
        deviceId = controller?.id ?: -1
        amplitude = 0
        refreshed = 0L
    }

    private fun isController(device: InputDevice) =
        !device.isVirtual &&
            (device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                device.supportsSource(InputDevice.SOURCE_JOYSTICK))

    private fun motor(device: InputDevice): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) device.vibratorManager.defaultVibrator
        else {
            @Suppress("DEPRECATION") device.vibrator
        }

    override fun onInputDeviceAdded(id: Int) {
        select(null)
    }

    override fun onInputDeviceRemoved(id: Int) {
        if (id == deviceId) select(null)
    }

    override fun onInputDeviceChanged(id: Int) {
        if (id == deviceId) {
            deviceId = -1
            select(InputDevice.getDevice(id))
        }
    }
}
