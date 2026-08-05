package family.remote.parent.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions

data class IncomingRequest(val id: String, val helperName: String)

class ParentRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()
) {
    fun registerDevice(deviceId: String, name: String, done: (Result<Unit>) -> Unit = {}) {
        functions.getHttpsCallable("registerDevice").call(mapOf("deviceId" to deviceId, "name" to name, "role" to "parent", "platform" to "android")).addOnCompleteListener { done(if (it.isSuccessful) Result.success(Unit) else Result.failure(it.exception ?: Exception("Device registration failed"))) }
    }

    fun signIn(email: String, password: String, done: (Result<Unit>) -> Unit) {
        auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { task ->
            done(if (task.isSuccessful) Result.success(Unit) else Result.failure(task.exception ?: Exception("Sign-in failed")))
        }
    }

    fun observeRequests(deviceId: String, update: (List<IncomingRequest>) -> Unit): ListenerRegistration =
        db.collection("sessionRequests").whereEqualTo("parentDeviceId", deviceId).addSnapshotListener { snapshot, _ ->
            update(snapshot?.documents?.mapNotNull { doc ->
                if (doc.getString("state") != "requested") null else IncomingRequest(doc.id, doc.getString("helperName") ?: "Family helper")
            }.orEmpty())
        }

    fun createPairingInvite(parentDeviceId: String, done: (Result<String>) -> Unit) {
        functions.getHttpsCallable("createPairingInvite").call(mapOf("parentDeviceId" to parentDeviceId)).addOnCompleteListener { task ->
            val code = (task.result?.data as? Map<*, *>)?.get("code") as? String
            done(if (task.isSuccessful && code != null) Result.success(code) else Result.failure(task.exception ?: Exception("Could not create code")))
        }
    }

    fun respond(requestId: String, accept: Boolean, done: (Result<String>) -> Unit) {
        functions.getHttpsCallable("respondToSession").call(mapOf("requestId" to requestId, "accept" to accept)).addOnCompleteListener { task ->
            val sessionId = (task.result?.data as? Map<*, *>)?.get("sessionId") as? String
            done(if (task.isSuccessful && (!accept || sessionId != null)) Result.success(sessionId.orEmpty()) else Result.failure(task.exception ?: Exception("Could not respond")))
        }
    }
}
