package family.remote.parent.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.ThreadUtils
import org.webrtc.VideoCapturer

/** One consent token, one virtual display. Resize it in place on Android 12+. */
class DisplayCapturer(private val consent: Intent, private val onRevoked: () -> Unit) : VideoCapturer {
    private lateinit var texture: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver
    private lateinit var manager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { onRevoked() }
    }

    override fun initialize(helper: SurfaceTextureHelper, context: Context, capturerObserver: CapturerObserver) {
        texture = helper
        observer = capturerObserver
        manager = context.getSystemService(MediaProjectionManager::class.java)
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        check(projection == null)
        val active = requireNotNull(manager.getMediaProjection(Activity.RESULT_OK, consent))
        projection = active
        active.registerCallback(callback, texture.handler)
        ThreadUtils.invokeAtFrontUninterruptibly(texture.handler, Runnable {
            texture.setTextureSize(width, height)
            surface = Surface(texture.surfaceTexture)
            display = active.createVirtualDisplay("KinPilot screen", width, height, 400,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, texture.handler)
            observer.onCapturerStarted(true)
            texture.startListening { frame -> observer.onFrameCaptured(frame) }
        })
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        ThreadUtils.invokeAtFrontUninterruptibly(texture.handler, Runnable {
            if (display != null) {
                texture.setTextureSize(width, height)
                display?.resize(width, height, 400)
            }
        })
    }

    override fun stopCapture() {
        if (!::texture.isInitialized) return
        ThreadUtils.invokeAtFrontUninterruptibly(texture.handler, Runnable {
            texture.stopListening()
            display?.release(); display = null
            surface?.release(); surface = null
            projection?.let { it.unregisterCallback(callback); it.stop() }
            projection = null
            observer.onCapturerStopped()
        })
    }

    override fun isScreencast() = true
    override fun dispose() = Unit
}
