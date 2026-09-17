package family.remote.parent.capture

import android.content.Intent
import android.util.Log
import family.remote.parent.rtc.ParentRtcEngine
import family.remote.protocol.VoiceState

object ScreenSessionCoordinator {
    @Volatile private var active = false
    private var engine: ParentRtcEngine? = null
    @Volatile var voiceListener: ((VoiceState, VoiceState?) -> Unit)? = null
    fun onForegroundServiceReady(intent: Intent?) {
        Log.i(TAG, "onForegroundServiceReady intent=${intent != null}")
        val projectionData = requireNotNull(intent?.getParcelableExtra<Intent>(ScreenShareService.EXTRA_RESULT_DATA))
        val resultCode = intent.getIntExtra(ScreenShareService.EXTRA_RESULT_CODE, 0)
        val sessionId = requireNotNull(intent.getStringExtra("sessionId"))
        Log.i(TAG, "building engine sessionId=$sessionId resultCode=$resultCode")
        engine = ParentRtcEngine(AppContext.value, sessionId, resultCode, projectionData)
        engine?.onRemoteVoiceState = { voiceListener?.invoke(engine?.localVoiceState() ?: VoiceState(), it) }
        active = true
        engine?.start()
        Log.i(TAG, "engine started")
    }
    fun joinVoice(): Boolean = engine?.joinVoice()?.also { if (it) voiceListener?.invoke(engine?.localVoiceState() ?: VoiceState(), null) } ?: false
    fun setVoiceMuted(muted: Boolean) { engine?.setVoiceMuted(muted); voiceListener?.invoke(engine?.localVoiceState() ?: VoiceState(), null) }
    fun leaveVoice(reason: String? = null) { engine?.leaveVoice(reason); voiceListener?.invoke(engine?.localVoiceState() ?: VoiceState(), null) }
    fun voiceState(): VoiceState = engine?.localVoiceState() ?: VoiceState()
    fun stop(reason: String) {
        val previous = engine
        engine = null
        val wasActive = active
        active = false
        previous?.close()
        voiceListener = null
        if (wasActive) SessionEvents.listener?.invoke(reason)
    }
    private const val TAG = "KinPilot/ScreenSession"
}

object SessionEvents { @Volatile var listener: ((String) -> Unit)? = null }
object AppContext { lateinit var value: android.content.Context }
