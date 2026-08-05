package family.remote.parent.capture

import android.content.Intent
import family.remote.parent.rtc.ParentRtcEngine

object ScreenSessionCoordinator {
    @Volatile private var active = false
    private var engine: ParentRtcEngine? = null
    fun onForegroundServiceReady(intent: Intent?) {
        val projectionData = requireNotNull(intent?.getParcelableExtra<Intent>(ScreenShareService.EXTRA_RESULT_DATA))
        val resultCode = intent.getIntExtra(ScreenShareService.EXTRA_RESULT_CODE, 0)
        val sessionId = requireNotNull(intent.getStringExtra("sessionId"))
        engine = ParentRtcEngine(AppContext.value, sessionId, resultCode, projectionData).also { it.start() }
        active = true
    }
    fun stop(reason: String) { engine?.close(); engine = null; if (active) { active = false; SessionEvents.listener?.invoke(reason) } }
}

object SessionEvents { @Volatile var listener: ((String) -> Unit)? = null }
object AppContext { lateinit var value: android.content.Context }
