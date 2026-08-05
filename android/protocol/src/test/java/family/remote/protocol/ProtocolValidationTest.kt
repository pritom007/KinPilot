package family.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolValidationTest {
    @Test fun acceptsNormalizedTap() = assertNull(ProtocolValidation.validate(ControlCommand.Tap(1, .5f, .25f)))
    @Test fun rejectsOutOfBoundsTap() = assertEquals("invalid_coordinate", ProtocolValidation.validate(ControlCommand.Tap(1, 1.1f, .25f)))
    @Test fun limitsTextWithoutLoggingIt() = assertEquals("text_too_long", ProtocolValidation.validate(ControlCommand.SetText(1, "x".repeat(2001))))
}
