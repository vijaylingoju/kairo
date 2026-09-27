package ai.kairo.gallery.settings

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Settings changed through system services rather than Settings.System: volumes, ringer, DND, torch. */
class DeviceController(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)

    // ---- Volume ----

    fun volume(stream: Int): Int = audio.getStreamVolume(stream)
    fun minVolume(stream: Int): Int = audio.getStreamMinVolume(stream)
    fun maxVolume(stream: Int): Int = audio.getStreamMaxVolume(stream)

    /** FLAG_SHOW_UI pops the system volume slider so the user sees the change. */
    fun setVolume(stream: Int, value: Int) {
        audio.setStreamVolume(stream, value.coerceIn(minVolume(stream), maxVolume(stream)), AudioManager.FLAG_SHOW_UI)
    }

    // ---- Ringer / Do Not Disturb (silent + DND need notification-policy access) ----

    fun hasPolicyAccess(): Boolean = notifications.isNotificationPolicyAccessGranted

    fun ringerMode(): Int = audio.ringerMode

    fun setRingerMode(mode: Int) {
        audio.ringerMode = mode
    }

    fun isDndOn(): Boolean = notifications.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun interruptionFilter(): Int = notifications.currentInterruptionFilter

    fun setInterruptionFilter(filter: Int) {
        notifications.setInterruptionFilter(filter)
    }

    // ---- Touch sounds (the Settings.System flag alone doesn't reload the sound pool) ----

    fun applyTouchSounds(on: Boolean) {
        if (on) audio.loadSoundEffects() else audio.unloadSoundEffects()
    }

    // ---- Flashlight ----

    private val torchId: String? = runCatching {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    /** There's no getter for torch state; the callback reports it right after registering and on every change. */
    @Volatile
    var isTorchOn = false
        private set

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) isTorchOn = enabled
        }
    }

    init {
        if (torchId != null) camera.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
    }

    fun hasFlash(): Boolean = torchId != null

    /** Throws CameraAccessException if the camera is in use (e.g. by the camera app). */
    fun setTorch(on: Boolean) {
        camera.setTorchMode(torchId ?: return, on)
        isTorchOn = on
    }

    fun close() {
        if (torchId != null) camera.unregisterTorchCallback(torchCallback)
    }
}
