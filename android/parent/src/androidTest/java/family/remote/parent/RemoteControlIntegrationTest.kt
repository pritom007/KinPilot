package family.remote.parent

import android.content.Intent
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import family.remote.parent.control.RemoteControlService
import family.remote.protocol.ControlCommand
import family.remote.protocol.ControlResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Accessibility gestures on an emulator; no permission bypass in production code. */
@RunWith(AndroidJUnit4::class)
class RemoteControlIntegrationTest {
    @Test fun executesRemoteActionsAndRejectsReplayAndEndedSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val component = "${context.packageName}/family.remote.parent.control.RemoteControlService"
        val oldServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        val oldEnabled = device.executeShellCommand("settings get secure accessibility_enabled").trim()
        try {
            val services = if (oldServices == "null" || oldServices.isBlank()) component else "$oldServices:$component"
            device.executeShellCommand("settings put secure enabled_accessibility_services $services")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!RemoteControlService.isAvailable() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue("Accessibility must bind before actions are tested", RemoteControlService.isAvailable())
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("KinPilot should become foreground", device.wait(Until.hasObject(By.pkg(context.packageName)), 10000))
            // The role cards extend below the fold on the CI emulator. Scroll only
            // during setup; the actual action assertions below use our service.
            if (!device.hasObject(By.text("Enter a code"))) {
                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text("Enter a code"))
            }
            val enter = device.wait(Until.findObject(By.text("Enter a code")), 10000)
            assertNotNull("Home screen should appear", enter)
            instrumentation.runOnMainSync { RemoteControlService.beginSession() }
            val bounds = enter.visibleBounds
            val wm = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            val tap = ControlCommand.Tap(1, bounds.centerX().toFloat() / (wm.width() - 1), bounds.centerY().toFloat() / (wm.height() - 1))
            assertTrue("Remote tap must complete", execute(tap).accepted)
            assertTrue("Tap must actually open the helper screen", device.wait(Until.hasObject(By.text("Your name")), 5000))
            assertEquals("replayed_command", execute(tap).reason)
            val field = device.findObject(By.text("Your name"))
            val f = field.visibleBounds
            assertTrue(execute(ControlCommand.Tap(2, f.centerX().toFloat() / (wm.width() - 1), f.centerY().toFloat() / (wm.height() - 1))).accepted)
            device.waitForIdle()
            assertTrue("Focused editable node must accept remote text", execute(ControlCommand.SetText(3, "Family helper")).accepted)
            assertTrue(device.wait(Until.hasObject(By.text("Family helper")), 5000))
            assertTrue(execute(ControlCommand.Swipe(4, .5f, .75f, .5f, .4f, 300)).accepted)
            assertTrue(execute(ControlCommand.GlobalAction(5, ControlCommand.Action.HOME)).accepted)
            assertTrue(device.wait(Until.gone(By.pkg(context.packageName)), 5000))
            instrumentation.runOnMainSync { RemoteControlService.endSession() }
            assertEquals("session_not_active", execute(ControlCommand.Tap(6, .5f, .5f)).reason)
        } catch (failure: Throwable) {
            val hierarchy = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            // Only the disposable emulator's synthetic test UI is included.
            println("Test UI at failure: " + hierarchy.toString("UTF-8"))
            throw failure
        } finally {
            instrumentation.runOnMainSync { RemoteControlService.endSession() }
            if (oldServices == "null") device.executeShellCommand("settings delete secure enabled_accessibility_services")
            else device.executeShellCommand("settings put secure enabled_accessibility_services '$oldServices'")
            if (oldEnabled == "null") device.executeShellCommand("settings delete secure accessibility_enabled")
            else device.executeShellCommand("settings put secure accessibility_enabled $oldEnabled")
        }
    }

    private fun execute(command: ControlCommand): ControlResult {
        val latch = CountDownLatch(1)
        var result: ControlResult? = null
        RemoteControlService.dispatch(command) { result = it; latch.countDown() }
        assertTrue("Control response should arrive", latch.await(5, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
}
