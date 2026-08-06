package family.remote.parent.capture

import android.content.Intent
import android.util.Log
import family.remote.parent.rtc.ParentRtcEngine

object ScreenSessionCoordinator {
    @Volatile private var active = false
    private var engine: ParentRtcEngine? = null
    fun onForegroundServiceReady(intent: Intent?) {
        Log.i(TAG, "onForegroundServiceReady intent=${intent != null}")
        val projectionData = requireNotNull(intent?.getParcelableExtra<Intent>(ScreenShareService.EXTRA_RESULT_DATA))
        val resultCode = intent.getIntExtra(ScreenShareService.EXTRA_RESULT_CODE, 0)
        val sessionId = requireNotNull(intent.getStringExtra("sessionId"))
        Log.i(TAG, "building engine sessionId=$sessionId resultCode=$resultCode")
        engine = ParentRtcEngine(AppContext.value, sessionId, resultCode, projectionData).also { it.start() }
        active = true
        Log.i(TAG, "engine started")
    }
    fun stop(reason: String) { Log.i(TAG, "stop reason=$reason active=$active"); engine?.close(); engine = null; if (active) { active = false; SessionEvents.listener?.invoke(reason) } }
    private const val TAG = "KinPilot/ScreenSession"
}

object SessionEvents { @Volatile var listener: ((String) -> Unit)? = null }
object AppContext { lateinit var value: android.content.Context }
