package family.remote.protocol

import org.junit.Assert.*
import org.junit.Test

class ScreenCoordinatesTest {
    @Test fun portraitInWideViewExcludesSideBars() {
        assertNull(ScreenCoordinates.normalize(10f, 100f, 1000, 1000, 500, 1000))
        assertEquals(.5f to .25f, ScreenCoordinates.normalize(500f, 250f, 1000, 1000, 500, 1000))
        assertEquals(0f to 0f, ScreenCoordinates.normalize(250f, 0f, 1000, 1000, 500, 1000))
    }
    @Test fun landscapeExcludesTopBars() {
        assertNull(ScreenCoordinates.normalize(500f, 10f, 1000, 1000, 1000, 500))
        assertEquals(.75f to .5f, ScreenCoordinates.normalize(750f, 500f, 1000, 1000, 1000, 500))
    }
    @Test fun rejectBeforeFirstFrameAndInvalidInput() {
        assertNull(ScreenCoordinates.normalize(0f, 0f, 100, 100, 0, 0))
        assertNull(ScreenCoordinates.normalize(Float.NaN, 1f, 100, 100, 100, 100))
        assertNull(ScreenCoordinates.normalize(101f, 1f, 100, 100, 100, 100))
    }
}
