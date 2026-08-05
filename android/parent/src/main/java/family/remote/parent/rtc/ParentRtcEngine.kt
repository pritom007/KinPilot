package family.remote.parent.rtc

import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import family.remote.parent.control.RemoteControlService
import family.remote.protocol.ControlCommand
import family.remote.protocol.ProtocolJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ParentRtcEngine(private val context: Context, private val sessionId: String, resultCode: Int, projectionData: Intent) : AutoCloseable {
    private val db = FirebaseFirestore.getInstance()
    private val uid = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid)
    private val egl = EglBase.create()
    private val capturer = ScreenCapturerAndroid(projectionData, object : android.media.projection.MediaProjection.Callback() { override fun onStop() = close() })
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private val source: VideoSource
    private val helperSignals = mutableListOf<ListenerRegistration>()
    private var channel: DataChannel? = null
    private var recipientId: String? = null
    private val closed = AtomicBoolean(false)

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val servers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        peer = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(servers), PeerObserver()))
        source = factory.createVideoSource(capturer.isScreencast)
    }

    fun start() {
        val texture = SurfaceTextureHelper.create("screen-capture", egl.eglBaseContext)
        capturer.initialize(texture, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 15)
        peer.addTrack(factory.createVideoTrack("screen", source), listOf("support"))
        channel = peer.createDataChannel("control", DataChannel.Init().apply { ordered = true }).also { observeChannel(it) }
        listenForSignals()
        FirebaseFunctions.getInstance().getHttpsCallable("getIceServers").call(mapOf("sessionId" to sessionId)).addOnSuccessListener { result ->
            parseIceServers(result.data)?.let { peer.setConfiguration(PeerConnection.RTCConfiguration(it)) }
            createOffer()
        }.addOnFailureListener { createOffer() }
    }

    private fun createOffer() {
        db.collection("sessions").document(sessionId).get().addOnSuccessListener { session ->
            recipientId = session.getString("helperUserId") ?: return@addOnSuccessListener
            peer.createOffer(SdpCallback { description ->
                peer.setLocalDescription(SdpCallback { publish("offer", description.description) }, description)
            }, MediaConstraints())
        }
    }

    private fun parseIceServers(data: Any?): List<PeerConnection.IceServer>? {
        val values = (data as? Map<*, *>)?.get("iceServers") as? List<*> ?: return null
        val parsed = values.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val urls = when (val value = map["urls"]) { is String -> listOf(value); is List<*> -> value.filterIsInstance<String>(); else -> emptyList() }
            if (urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(urls).setUsername(map["username"] as? String ?: "").setPassword(map["credential"] as? String ?: "").createIceServer()
        }
        return listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) + parsed
    }

    private fun listenForSignals() {
        helperSignals += db.collection("sessions").document(sessionId).collection("signals")
            .whereEqualTo("recipientId", uid).addSnapshotListener { snapshot, _ ->
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) return@forEach
                    val kind = change.document.getString("kind") ?: return@forEach
                    val payload = change.document.getString("payload") ?: return@forEach
                    when (kind) {
                        "answer" -> peer.setRemoteDescription(SdpCallback {}, SessionDescription(SessionDescription.Type.ANSWER, payload))
                        "ice" -> decodeIce(payload)?.let(peer::addIceCandidate)
                    }
                }
            }
    }

    private fun publish(kind: String, payload: String) {
        val recipient = recipientId ?: return
        db.collection("sessions").document(sessionId).collection("signals").document(UUID.randomUUID().toString()).set(mapOf(
            "senderId" to uid, "recipientId" to recipient, "kind" to kind, "payload" to payload,
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        ))
    }

    private fun observeChannel(dataChannel: DataChannel) = dataChannel.registerObserver(object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit
        override fun onStateChange() = Unit
        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary) return
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
            val result = runCatching { ProtocolJson.decodeFromString<ControlCommand>(bytes.decodeToString()) }
                .map(RemoteControlService::dispatch).getOrElse { return }
            val response = ProtocolJson.encodeToString(result).encodeToByteArray()
            dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(response), false))
        }
    })

    private inner class PeerObserver : PeerConnection.Observer by EmptyPeerObserver() {
        override fun onIceCandidate(candidate: IceCandidate) = publish("ice", "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
        override fun onDataChannel(dc: DataChannel) { channel = dc; observeChannel(dc) }
    }

    private fun decodeIce(value: String): IceCandidate? { val p = value.split('|', limit = 3); return if (p.size == 3) IceCandidate(p[0], p[1].toIntOrNull() ?: return null, p[2]) else null }
    override fun close() { if (!closed.compareAndSet(false, true)) return; FirebaseFunctions.getInstance().getHttpsCallable("endSession").call(mapOf("sessionId" to sessionId, "reason" to "parent_stopped")); helperSignals.forEach { it.remove() }; runCatching { capturer.stopCapture() }; capturer.dispose(); channel?.dispose(); peer.close(); source.dispose(); factory.dispose(); egl.release() }
}

private open class EmptyPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) = Unit; override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(p0: Boolean) = Unit; override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(p0: IceCandidate?) = Unit; override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) = Unit
    override fun onAddStream(p0: MediaStream?) = Unit; override fun onRemoveStream(p0: MediaStream?) = Unit; override fun onDataChannel(p0: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit; override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) = Unit
}

private class SdpCallback(private val success: (SessionDescription) -> Unit) : SdpObserver {
    override fun onCreateSuccess(value: SessionDescription) = success(value); override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String?) = Unit; override fun onSetFailure(error: String?) = Unit
}
