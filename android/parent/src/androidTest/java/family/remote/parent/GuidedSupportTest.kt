package family.remote.parent

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedSupportTest {
    @Test fun homeShortcutOpensSupportWithoutRoleSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent(context, MainActivity::class.java)
            .putExtra("getHelp", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue("Get help shortcut must open support directly",
            device.wait(Until.hasObject(By.text("Help with my phone")), 10000))
        device.pressBack()
        assertTrue("Back must return to the home screen",
            device.wait(Until.hasObject(By.text("Get help with my phone")), 10000))
    }

    @Test fun phoneToolsAreAvailableWithoutStartingASession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Phone tools"))
        val tools = device.wait(Until.findObject(By.text("Phone tools")), 5000)
        assertNotNull(tools)
        tools.click()
        for (title in listOf("Internet connection", "Sound and volume", "Text and screen size", "Battery")) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(title))
            assertTrue("Missing tool: $title", device.wait(Until.hasObject(By.text(title)), 5000))
        }
    }
}
