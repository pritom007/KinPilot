package family.remote.parent.rtc

import android.content.Context
import android.content.Intent
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
    private val capturer = ScreenCapturerAndroid(projectionData, object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() = close()
    })
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val source: VideoSource
    private var channel: DataChannel? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    init {
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
        source = factory.createVideoSource(capturer.isScreencast)
    }

    fun start() {
        client.signalListener = { kind, payload ->
            when (kind) {
                "answer" -> peer.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(v: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        remoteSet = true
                        val buffered = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
                        buffered.forEach { peer.addIceCandidate(it) }
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?) = Unit
                }, SessionDescription(SessionDescription.Type.ANSWER, payload))
                "ice" -> decodeIce(payload)?.let { c ->
                    if (remoteSet) peer.addIceCandidate(c)
                    else synchronized(pendingIce) { pendingIce.add(c) }
                }
            }
        }
        val texture = SurfaceTextureHelper.create("screen-capture", egl.eglBaseContext)
        capturer.initialize(texture, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 15)
        peer.addTrack(factory.createVideoTrack("screen", source), listOf("support"))
        channel = peer.createDataChannel("control", DataChannel.Init().apply { ordered = true }).also(::observe)
        peer.createOffer(Sdp { offer ->
            peer.setLocalDescription(Sdp { client.signal("offer", offer.description) }, offer)
        }, MediaConstraints())
    }

    private fun observe(dc: DataChannel) = dc.registerObserver(object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val result = runCatching { ProtocolJson.decodeFromString<ControlCommand>(bytes.decodeToString()) }
                .map(RemoteControlService::dispatch).getOrElse { return }
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(ProtocolJson.encodeToString(result).encodeToByteArray()), false))
        }
    })

    private inner class Observer : PeerConnection.Observer by Base() {
        override fun onIceCandidate(c: IceCandidate) =
            client.signal("ice", JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp).toString())
        override fun onDataChannel(dc: DataChannel) { channel = dc; observe(dc) }
    }

    private fun decodeIce(v: String) = runCatching {
        val j = JSONObject(v); IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.getString("candidate"))
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.signalListener = null
        runCatching { capturer.stopCapture() }
        capturer.dispose(); channel?.dispose(); peer.close(); source.dispose(); factory.dispose(); egl.release()
    }
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
    override fun onCreateFailure(e: String?) = Unit
    override fun onSetFailure(e: String?) = Unit
}
