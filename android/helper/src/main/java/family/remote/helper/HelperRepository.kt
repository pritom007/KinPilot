package family.remote.helper

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions

data class ParentDevice(val id: String, val name: String)

class HelperRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance(), private val db: FirebaseFirestore = FirebaseFirestore.getInstance(), private val functions: FirebaseFunctions = FirebaseFunctions.getInstance()) {
    fun registerDevice(deviceId: String, name: String) = functions.getHttpsCallable("registerDevice").call(mapOf("deviceId" to deviceId, "name" to name, "role" to "helper", "platform" to "android"))
    fun signIn(email: String, password: String, done: (Result<Unit>) -> Unit) = auth.signInWithEmailAndPassword(email.trim(), password).addOnCompleteListener { done(if (it.isSuccessful) Result.success(Unit) else Result.failure(it.exception ?: Exception("Sign-in failed"))) }
    fun redeem(code: String, helperName: String, done: (Result<Unit>) -> Unit) = functions.getHttpsCallable("redeemPairingInvite").call(mapOf("code" to code, "helperName" to helperName)).addOnCompleteListener { done(if (it.isSuccessful) Result.success(Unit) else Result.failure(it.exception ?: Exception("Pairing failed"))) }
    fun observeDevices(update: (List<ParentDevice>) -> Unit): ListenerRegistration {
        val uid = requireNotNull(auth.currentUser?.uid)
        return db.collection("trustedHelpers").whereEqualTo("helperUserId", uid).whereEqualTo("revokedAt", null).addSnapshotListener { trusts, _ ->
            val ids = trusts?.documents?.mapNotNull { it.getString("parentDeviceId") }.orEmpty()
            if (ids.isEmpty()) update(emptyList()) else db.collection("devices").whereIn(com.google.firebase.firestore.FieldPath.documentId(), ids.take(10)).get().addOnSuccessListener { docs -> update(docs.map { ParentDevice(it.id, it.getString("name") ?: "Parent phone") }) }
        }
    }
    fun request(deviceId: String, helperName: String, done: (Result<String>) -> Unit) = functions.getHttpsCallable("requestSession").call(mapOf("parentDeviceId" to deviceId, "helperName" to helperName)).addOnCompleteListener { task ->
        val requestId = (task.result?.data as? Map<*, *>)?.get("requestId") as? String; done(if (task.isSuccessful && requestId != null) Result.success(requestId) else Result.failure(task.exception ?: Exception("Request failed")))
    }
    fun watchAccepted(requestId: String, accepted: (String) -> Unit): ListenerRegistration = db.collection("sessionRequests").document(requestId).addSnapshotListener { value, _ -> if (value?.getString("state") == "accepted") value.getString("sessionId")?.let(accepted) }
}
