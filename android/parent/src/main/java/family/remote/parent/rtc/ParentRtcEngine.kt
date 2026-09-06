package family.remote.parent.rtc

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.hardware.display.DisplayManager
import family.remote.parent.ParentSessionState
import family.remote.parent.control.RemoteControlService
import family.remote.protocol.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ParentRtcEngine(
    private val context: Context,
    private val sessionId: String,
    resultCode: Int,
    projectionData: Intent
) : AutoCloseable {
    private val client = requireNotNull(ParentSessionState.client)
    private val egl = EglBase.create()
    private val capturer = family.remote.parent.capture.DisplayCapturer(projectionData) {
        Handler(Looper.getMainLooper()).post {
            context.stopService(Intent(context, family.remote.parent.capture.ScreenShareService::class.java))
        }
    }
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val source: VideoSource
    private var channel: DataChannel? = null
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var texture: SurfaceTextureHelper? = null
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (!closed.get() && displayId == android.view.Display.DEFAULT_DISPLAY) {
                val (width, height) = captureSize()
                capturer.changeCaptureFormat(width, height, 15)
            }
        }
    }
    private val availabilityListener: (Boolean) -> Unit = ::sendControlStatus
    @Volatile private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    init {
        Log.i(TAG, "init sessionId=$sessionId resultCode=$resultCode")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        peer = requireNotNull(factory.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )), Observer()))
        source = factory.createVideoSource(capturer.isScreencast())
        Log.i(TAG, "factory+peer+source ready")
    }

    fun start() {
        Log.i(TAG, "start: installing signal listener, init capturer")
        RemoteControlService.addAvailabilityListener(availabilityListener)
        client.signalListener = { kind, payload ->
            Log.d(TAG, "signal<- kind=$kind len=${payload.length}")
            when (kind) {
                "answer" -> peer.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(v: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        remoteSet = true
                        Log.i(TAG, "remote answer set; flushing ${pendingIce.size} pending ICE")
                        val buffered = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
                        buffered.forEach { peer.addIceCandidate(it) }
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setRemoteDescription(answer) failed: $e") }
                }, SessionDescription(SessionDescription.Type.ANSWER, payload))
                "ice" -> decodeIce(payload)?.let { c ->
                    if (remoteSet) { Log.d(TAG, "addIce immediate"); peer.addIceCandidate(c) }
                    else { Log.d(TAG, "queue ICE pre-answer"); synchronized(pendingIce) { pendingIce.add(c) } }
                }
                else -> Log.w(TAG, "unknown signal kind=$kind")
            }
        }
        val captureTexture = requireNotNull(SurfaceTextureHelper.create("screen-capture", egl.eglBaseContext))
        texture = captureTexture
        capturer.initialize(captureTexture, context, source.capturerObserver)
        val (width, height) = captureSize()
        capturer.startCapture(width, height, 15)
        displayManager.registerDisplayListener(displayListener, mainHandler)
        Log.i(TAG, "capturer.startCapture done")
        peer.addTrack(factory.createVideoTrack("screen", source), listOf("support"))
        channel = peer.createDataChannel("control", DataChannel.Init().apply { ordered = true })
        channel?.let(::observe)
        peer.createOffer(Sdp { offer ->
            Log.i(TAG, "offer created len=${offer.description.length}")
            peer.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(v: SessionDescription?) = Unit
                override fun onSetSuccess() {
                    Log.i(TAG, "local offer set; signaling to helper")
                    client.signal("offer", offer.description)
                }
                override fun onCreateFailure(e: String?) = Unit
                override fun onSetFailure(e: String?) { Log.e(TAG, "setLocalDescription(offer) failed: $e") }
            }, offer)
        }, MediaConstraints())
    }

    private fun observe(dc: DataChannel) = dc.registerObserver(object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() {
            Log.i(TAG, "datachannel state=${dc.state()}")
            mainHandler.post { if (!closed.get() && dc.state() == DataChannel.State.OPEN) sendControlStatus(RemoteControlService.isAvailable()) }
        }
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            if (buffer.data.remaining() > 16384) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            // Leave WebRTC's callback thread before calling Android services or sending a reply.
            mainHandler.post {
                if (closed.get()) return@post
                val json = bytes.decodeToString()
                if (runCatching { JSONObject(json).optString("type") }.getOrNull() == "controlStatusRequest") {
                    sendControlStatus(RemoteControlService.isAvailable())
                    return@post
                }
                val command = runCatching { ProtocolJson.decodeFromString<ControlCommand>(json) }.getOrNull() ?: return@post
                RemoteControlService.dispatch(command) { result ->
                    if (!closed.get() && dc.state() == DataChannel.State.OPEN) {
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(ProtocolJson.encodeToString(result).encodeToByteArray()), false))
                    }
                }
            }
        }
    })

    private fun sendControlStatus(ready: Boolean) {
        if (closed.get()) return
        val activeChannel = channel?.takeIf { it.state() == DataChannel.State.OPEN } ?: return
        val status = ControlStatus(ready = ready, reason = if (ready) null else "accessibility_unavailable")
        activeChannel.send(DataChannel.Buffer(ByteBuffer.wrap(ProtocolJson.encodeToString(status).encodeToByteArray()), false))
    }

    private fun captureSize(): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val scale = minOf(1f, 1280f / maxOf(bounds.width(), bounds.height()))
        return maxOf(2, (bounds.width() * scale).toInt() / 2 * 2) to
            maxOf(2, (bounds.height() * scale).toInt() / 2 * 2)
    }

    private inner class Observer : PeerConnection.Observer by Base() {
        override fun onIceCandidate(c: IceCandidate) {
            Log.d(TAG, "onIceCandidate ->${c.sdpMid}:${c.sdpMLineIndex}")
            client.signal("ice", JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp).toString())
        }
        override fun onIceConnectionChange(v: PeerConnection.IceConnectionState?) { Log.i(TAG, "iceConn=$v") }
        override fun onIceGatheringChange(v: PeerConnection.IceGatheringState?) { Log.i(TAG, "iceGather=$v") }
        override fun onDataChannel(dc: DataChannel) { Log.i(TAG, "onDataChannel"); channel = dc; observe(dc) }
    }

    private fun decodeIce(v: String) = runCatching {
        val j = JSONObject(v); IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.getString("candidate"))
    }.onFailure { Log.w(TAG, "decodeIce failed: ${it.message}") }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "close")
        RemoteControlService.endSession()
        displayManager.unregisterDisplayListener(displayListener)
        mainHandler.removeCallbacksAndMessages(null)
        RemoteControlService.removeAvailabilityListener(availabilityListener)
        client.signalListener = null
        // Order matters: stop the screen capturer and dispose the source BEFORE
        // closing the peer, otherwise the native VideoSource can be torn down
        // while the peer still references it (crashes libjingle on close).
        runCatching { capturer.stopCapture() }
        source.dispose()
        capturer.dispose()
        texture?.dispose()
        channel?.dispose()
        peer.close()
        factory.dispose()
        egl.release()
    }
    companion object { private const val TAG = "KinPilot/ParentRTC" }
}

private open class Base : PeerConnection.Observer {
    override fun onSignalingChange(v: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(v: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(v: Boolean) = Unit
    override fun onIceGatheringChange(v: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(v: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(v: Array<out IceCandidate>?) = Unit
    override fun onAddStream(v: MediaStream?) = Unit
    override fun onRemoveStream(v: MediaStream?) = Unit
    override fun onDataChannel(v: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(v: RtpReceiver?, s: Array<out MediaStream>?) = Unit
}

private class Sdp(private val ok: (SessionDescription) -> Unit) : SdpObserver {
    override fun onCreateSuccess(v: SessionDescription) = ok(v)
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(e: String?) { Log.e("KinPilot/ParentRTC", "onCreateFailure: $e") }
    override fun onSetFailure(e: String?) { Log.e("KinPilot/ParentRTC", "onSetFailure: $e") }
}
