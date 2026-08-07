package family.remote.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolValidationTest {
    @Test fun acceptsNormalizedTap() = assertNull(ProtocolValidation.validate(ControlCommand.Tap(1, .5f, .25f)))
    @Test fun rejectsOutOfBoundsTap() = assertEquals("invalid_coordinate", ProtocolValidation.validate(ControlCommand.Tap(1, 1.1f, .25f)))
    @Test fun limitsTextWithoutLoggingIt() = assertEquals("text_too_long", ProtocolValidation.validate(ControlCommand.SetText(1, "x".repeat(2001))))

    @Test fun roundTripsUnavailableControlStatus() {
        val status = ControlStatus(ready = false, reason = "accessibility_unavailable")
        val encoded = ProtocolJson.encodeToString(status)
        val decoded = ProtocolJson.decodeFromString<ControlStatus>(encoded)

        assertEquals("controlStatus", decoded.type)
        assertFalse(decoded.ready)
        assertEquals("accessibility_unavailable", decoded.reason)
    }
}
