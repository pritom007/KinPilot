package family.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class SupportCodeTest {
    @Test fun acceptsCodesAndOwnQrLinks() {
        assertEquals("ABCDEFGHIJKL", SupportCode.parse("abcd-efgh-ijkl"))
        assertEquals("ABCDEFGHIJKL", SupportCode.parse("https://kinpilot.netlify.app/join#ABCD-EFGH-IJKL"))
    }
    @Test fun rejectsIncompleteAndForeignLinks() {
        assertNull(SupportCode.parse("ABCD"))
        assertNull(SupportCode.parse("https://evil.example/join#ABCDEFGHIJKL"))
        assertNull(SupportCode.parse("https://kinpilot.netlify.app.evil.example/join#ABCDEFGHIJKL"))
    }
}
