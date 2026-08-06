package family.remote.protocol

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class RendezvousClient(
    private val listener: Listener,
    private val url: String = DEFAULT_URL
) : AutoCloseable {
    private val http = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private val json = "application/json".toMediaType()
    private val generation = AtomicLong()
    @Volatile private var clientId: String? = null
    @Volatile private var connecting = false
    @Volatile private var polling = false
    @Volatile var signalListener: ((String, String) -> Unit)? = null

    @Synchronized
    fun connect() {
        if (connecting || clientId != null) {
            Log.i(TAG, "connect ignored; connecting=$connecting clientId=$clientId")
            return
        }
        connecting = true
        val expectedGeneration = generation.get()
        Log.i(TAG, "connect -> $url")
        request(
            path = "/api/connect",
            body = JSONObject(),
            success = { response ->
                if (generation.get() != expectedGeneration) return@request
                clientId = response.getString("clientId")
                connecting = false
                polling = true
                Log.i(TAG, "connected clientId=$clientId")
                listener.onOpen()
                poll()
            },
            failure = {
                if (generation.get() == expectedGeneration) connecting = false
            }
        )
    }

    fun createRoom() = send(JSONObject().put("type", "create"))
    fun join(code: String, name: String) = send(JSONObject()
        .put("type", "join")
        .put("code", code)
        .put("helperName", name.take(60)))
    fun respond(id: String, accept: Boolean) = send(JSONObject()
        .put("type", "respond")
        .put("requestId", id)
        .put("accept", accept))
    fun signal(kind: String, payload: String) {
        if (payload.length <= 32 * 1024) send(JSONObject()
            .put("type", "signal")
            .put("kind", kind)
            .put("payload", payload))
        else Log.w(TAG, "signal dropped too-large kind=$kind")
    }

    private fun poll() {
        val id = clientId ?: return
        if (!polling) return
        http.newCall(Request.Builder().url("$url/api/poll?clientId=$id").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                Log.w(TAG, "poll failed: ${error.message}")
                again()
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) runCatching {
                        JSONObject(it.body?.string().orEmpty()).getJSONArray("messages")
                    }.getOrNull()?.let { messages ->
                        for (index in 0 until messages.length()) dispatch(messages.getJSONObject(index))
                    }
                }
                again()
            }
        })
    }

    private fun again() {
        if (polling) handler.postDelayed(::poll, 700)
    }

    private fun send(message: JSONObject) {
        val id = clientId ?: run {
            Log.w(TAG, "send without clientId: ${message.optString("type")}")
            return
        }
        request("/api/message", JSONObject().put("clientId", id).put("message", message), success = {})
    }

    private fun request(
        path: String,
        body: JSONObject,
        success: (JSONObject) -> Unit,
        failure: () -> Unit = {}
    ) {
        http.newCall(Request.Builder().url(url + path).post(body.toString().toRequestBody(json)).build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    Log.w(TAG, "$path failed: ${error.message}")
                    failure()
                    listener.onError("service_unavailable")
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.w(TAG, "$path -> ${it.code}")
                            failure()
                            listener.onError("service_unavailable")
                            return
                        }
                        val parsed = try {
                            JSONObject(it.body?.string().orEmpty())
                        } catch (_: JSONException) {
                            Log.w(TAG, "$path returned non-JSON")
                            failure()
                            listener.onError("service_unavailable")
                            return
                        }
                        success(parsed)
                    }
                }
            })
    }

    private fun dispatch(value: JSONObject) {
        when (value.optString("type")) {
            "room-created" -> listener.onRoomCreated(value.getString("code"), value.getLong("expiresAt"))
            "join-request" -> listener.onJoinRequest(value.getString("requestId"), value.optString("helperName", "Family helper"))
            "waiting" -> listener.onWaiting()
            "accepted" -> listener.onAccepted(value.getLong("expiresAt"))
            "declined" -> listener.onDeclined()
            "signal" -> signalListener?.invoke(value.getString("kind"), value.getString("payload"))
            "ended" -> listener.onEnded(value.optString("reason", "ended"))
            "error" -> listener.onError(value.optString("code", "unknown"))
            "pong" -> Unit
            else -> Log.w(TAG, "unknown message type=${value.optString("type")}")
        }
    }

    @Synchronized
    override fun close() {
        Log.i(TAG, "close")
        generation.incrementAndGet()
        connecting = false
        polling = false
        handler.removeCallbacksAndMessages(null)
        val id = clientId
        if (id != null) request(
            "/api/message",
            JSONObject().put("clientId", id).put("message", JSONObject().put("type", "leave")),
            success = {}
        )
        clientId = null
        signalListener = null
    }

    interface Listener {
        fun onOpen() {}
        fun onRoomCreated(code: String, expiresAt: Long) {}
        fun onJoinRequest(requestId: String, helperName: String) {}
        fun onWaiting() {}
        fun onAccepted(expiresAt: Long) {}
        fun onDeclined() {}
        fun onEnded(reason: String) {}
        fun onError(code: String) {}
    }

    companion object {
        const val DEFAULT_URL = "https://kinpilot-rendezvous.onrender.com"
        private const val TAG = "KinPilot/Rendezvous"
    }
}
