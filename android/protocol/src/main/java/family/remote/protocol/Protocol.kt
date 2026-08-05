package family.remote.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DeviceRole { PARENT, HELPER }

@Serializable
data class Device(val id: String, val ownerId: String, val name: String, val role: DeviceRole, val platform: String = "android", val lastSeenAtEpochMs: Long)

@Serializable
data class TrustedHelper(val parentDeviceId: String, val helperUserId: String, val helperName: String, val createdAtEpochMs: Long, val revokedAtEpochMs: Long? = null)

@Serializable
data class PairingInvite(val id: String, val parentDeviceId: String, val expiresAtEpochMs: Long, val redeemedAtEpochMs: Long? = null)

@Serializable
data class SessionRequest(val id: String, val parentDeviceId: String, val helperUserId: String, val helperName: String, val createdAtEpochMs: Long)

@Serializable
enum class SessionState { REQUESTED, ACCEPTED, CONNECTING, ACTIVE, ENDED, DECLINED, EXPIRED }

@Serializable
data class Session(val id: String, val parentDeviceId: String, val helperUserId: String, val state: SessionState, val createdAtEpochMs: Long, val expiresAtEpochMs: Long, val endedReason: String? = null)

@Serializable
data class SignalMessage(val sessionId: String, val senderId: String, val kind: SignalKind, val payload: String, val createdAtEpochMs: Long)

@Serializable
enum class SignalKind { OFFER, ANSWER, ICE }

@Serializable
data class ClientCapabilities(val protocolVersion: Int = CURRENT_PROTOCOL_VERSION, val video: Boolean = true, val control: Boolean = true, val textInput: Boolean = true, val audio: Boolean = false)

@Serializable
sealed class ControlCommand {
    abstract val sequence: Long

    @Serializable @SerialName("tap")
    data class Tap(override val sequence: Long, val x: Float, val y: Float) : ControlCommand()

    @Serializable @SerialName("swipe")
    data class Swipe(override val sequence: Long, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val durationMs: Long) : ControlCommand()

    @Serializable @SerialName("longPress")
    data class LongPress(override val sequence: Long, val x: Float, val y: Float) : ControlCommand()

    @Serializable @SerialName("globalAction")
    data class GlobalAction(override val sequence: Long, val action: Action) : ControlCommand()

    @Serializable @SerialName("setText")
    data class SetText(override val sequence: Long, val text: String) : ControlCommand()

    @Serializable enum class Action { BACK, HOME, RECENTS }
}

@Serializable
data class ControlResult(val sequence: Long, val accepted: Boolean, val reason: String? = null)

const val CURRENT_PROTOCOL_VERSION = 1
val ProtocolJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = false; encodeDefaults = true }

object ProtocolValidation {
    fun validate(command: ControlCommand): String? = when (command) {
        is ControlCommand.Tap -> coordinateError(command.x, command.y)
        is ControlCommand.LongPress -> coordinateError(command.x, command.y)
        is ControlCommand.Swipe -> coordinateError(command.fromX, command.fromY)
            ?: coordinateError(command.toX, command.toY)
            ?: if (command.durationMs !in 50..2_000) "invalid_duration" else null
        is ControlCommand.SetText -> if (command.text.length > 2_000) "text_too_long" else null
        is ControlCommand.GlobalAction -> null
    }

    private fun coordinateError(x: Float, y: Float): String? = if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) "invalid_coordinate" else null
}

