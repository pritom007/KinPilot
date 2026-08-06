package family.remote.helper

import android.content.Context
import family.remote.protocol.ControlCommand
import family.remote.protocol.ProtocolJson
import family.remote.protocol.RendezvousClient
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HelperRtcEngine(private val context: Context, private val client: RendezvousClient) : AutoCloseable {
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var channel: DataChannel? = null
    @Volatile private var renderer: SurfaceViewRenderer? = null
    @Volatile private var rendererInitialised = false
    @Volatile private var remoteTrack: VideoTrack? = null
    @Volatile private var remoteSet = false
    private val pendingIce = mutableListOf<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        peer = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(ice), Observer()))
    }

    fun start() {
        client.signalListener = { kind, payload ->
            when (kind) {
                "offer" -> peer.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(v: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        remoteSet = true
                        val buffered = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
                        buffered.forEach { peer.addIceCandidate(it) }
                        peer.createAnswer(Sdp { answer ->
                            peer.setLocalDescription(Sdp { client.signal("answer", answer.description) }, answer)
                        }, MediaConstraints())
                    }
                    override fun onCreateFailure(e: String?) = Unit
                    override fun onSetFailure(e: String?) = Unit
                }, SessionDescription(SessionDescription.Type.OFFER, payload))
                "ice" -> decodeIce(payload)?.let { c ->
                    if (remoteSet) peer.addIceCandidate(c)
                    else synchronized(pendingIce) { pendingIce.add(c) }
                }
            }
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        renderer = view
        if (!rendererInitialised) { view.init(egl.eglBaseContext, null); view.setEnableHardwareScaler(true); rendererInitialised = true }
        // If the remote track already arrived before the view was ready, wire it up now.
        remoteTrack?.addSink(view)
    }

    fun next() = sequence.incrementAndGet()

    fun send(command: ControlCommand) {
        channel?.takeIf { it.state() == DataChannel.State.OPEN }
            ?.send(DataChannel.Buffer(ByteBuffer.wrap(ProtocolJson.encodeToString(command).encodeToByteArray()), false))
    }

    private inner class Observer : PeerConnection.Observer by Base() {
        override fun onIceCandidate(c: IceCandidate) =
            client.signal("ice", JSONObject().put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex).put("candidate", c.sdp).toString())
        override fun onDataChannel(dc: DataChannel) { channel = dc }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            remoteTrack = track
            renderer?.let { track.addSink(it) }
        }
    }

    private fun decodeIce(value: String) = runCatching {
        val j = JSONObject(value); IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.getString("candidate"))
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.signalListener = null
        renderer?.release(); channel?.dispose(); peer.close(); factory.dispose(); egl.release()
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
