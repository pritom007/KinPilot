package family.remote.parent.rtc

import android.content.Context
import android.util.Log
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
        Log.i(TAG, "init")
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
        peer = requireNotNull(factory.createPeerConnection(
            PeerConnection.RTCConfiguration(listOf(
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )), Observer()
        ))
        Log.i(TAG, "factory+peer ready")
    }

    fun start() {
        Log.i(TAG, "start: installing signal listener")
        client.signalListener = { kind, payload ->
            Log.d(TAG, "signal<- kind=$kind len=${payload.length}")
            when (kind) {
                "offer" -> peer.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(value: SessionDescription?) = Unit
                    override fun onSetSuccess() {
                        remoteSet = true
                        val buffered = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
                        Log.i(TAG, "remote offer set; flushing ${buffered.size} pending ICE")
                        buffered.forEach(peer::addIceCandidate)
                        peer.createAnswer(HelperSdpObserver { answer ->
                            Log.i(TAG, "answer created len=${answer.description.length}")
                            peer.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(value: SessionDescription?) = Unit
                                override fun onSetSuccess() {
                                    Log.i(TAG, "local answer set; signaling to parent")
                                    client.signal("answer", answer.description)
                                }
                                override fun onCreateFailure(error: String?) = Unit
                                override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDescription(answer) failed: $error") }
                            }, answer)
                        }, MediaConstraints())
                    }
                    override fun onCreateFailure(error: String?) = Unit
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDescription(offer) failed: $error") }
                }, SessionDescription(SessionDescription.Type.OFFER, payload))
                "ice" -> decodeIce(payload)?.let { candidate ->
                    if (remoteSet) peer.addIceCandidate(candidate)
                    else synchronized(pendingIce) { pendingIce.add(candidate) }
                }
            }
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        renderer = view
        if (!rendererInitialised) {
            view.init(egl.eglBaseContext, null)
            view.setEnableHardwareScaler(true)
            view.setMirror(false)
            rendererInitialised = true
        }
        remoteTrack?.addSink(view)
    }

    fun next() = sequence.incrementAndGet()

    fun send(command: ControlCommand): Boolean {
        val activeChannel = channel?.takeIf { it.state() == DataChannel.State.OPEN } ?: return false
        return activeChannel.send(DataChannel.Buffer(
            ByteBuffer.wrap(ProtocolJson.encodeToString(command).encodeToByteArray()), false
        ))
    }

    private inner class Observer : PeerConnection.Observer by HelperPeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) {
            client.signal("ice", JSONObject()
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                .put("candidate", candidate.sdp)
                .toString())
        }
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "iceConn=$state")
        }
        override fun onDataChannel(dataChannel: DataChannel) {
            Log.i(TAG, "onDataChannel state=${dataChannel.state()}")
            channel = dataChannel
        }
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track() as? VideoTrack ?: return
            Log.i(TAG, "onAddTrack video track=${track.id()}")
            remoteTrack = track
            renderer?.let(track::addSink)
        }
    }

    private fun decodeIce(value: String) = runCatching {
        val json = JSONObject(value)
        IceCandidate(json.optString("sdpMid"), json.optInt("sdpMLineIndex"), json.getString("candidate"))
    }.getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "close")
        client.signalListener = null
        remoteTrack?.let { track -> renderer?.let(track::removeSink) }
        renderer?.release()
        channel?.dispose()
        peer.close()
        factory.dispose()
        egl.release()
    }

    companion object { private const val TAG = "KinPilot/HelperRTC" }
}

private open class HelperPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(value: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(value: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(value: Boolean) = Unit
    override fun onIceGatheringChange(value: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(value: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(value: Array<out IceCandidate>?) = Unit
    override fun onAddStream(value: MediaStream?) = Unit
    override fun onRemoveStream(value: MediaStream?) = Unit
    override fun onDataChannel(value: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(value: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
}

private class HelperSdpObserver(private val onCreated: (SessionDescription) -> Unit) : SdpObserver {
    override fun onCreateSuccess(value: SessionDescription) = onCreated(value)
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) { Log.e("KinPilot/HelperRTC", "create SDP failed: $error") }
    override fun onSetFailure(error: String?) { Log.e("KinPilot/HelperRTC", "set SDP failed: $error") }
}
