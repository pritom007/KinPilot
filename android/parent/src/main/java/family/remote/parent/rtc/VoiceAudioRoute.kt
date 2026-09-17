package family.remote.parent.rtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

class VoiceAudioRoute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    var label: String = "Speaker"
        private set

    fun begin() { audio.mode = AudioManager.MODE_IN_COMMUNICATION; select("Speaker") }

    fun cycle(): String {
        val devices = runCatching { audio.availableCommunicationDevices }.getOrDefault(emptyList())
        val supported = buildList {
            add("Speaker")
            if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) add("Earpiece")
            if (devices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) add("Wired headset")
            if (devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }) add("Bluetooth")
        }
        return select(supported[(supported.indexOf(label).takeIf { it >= 0 } ?: 0).let { (it + 1) % supported.size }])
    }

    private fun select(target: String): String {
        val types = when (target) {
            "Earpiece" -> setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            "Wired headset" -> setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            "Bluetooth" -> setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            else -> setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }
        runCatching { audio.availableCommunicationDevices.firstOrNull { it.type in types }?.let(audio::setCommunicationDevice) }
        label = target
        return label
    }

    fun end() { runCatching { audio.clearCommunicationDevice() }; audio.mode = AudioManager.MODE_NORMAL; label = "Speaker" }
}
