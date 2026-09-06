package family.remote.parent.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.KeyguardManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import family.remote.protocol.ControlCommand
import family.remote.protocol.ControlResult
import family.remote.protocol.ProtocolValidation
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

class RemoteControlService : AccessibilityService() {
    private val lastSequence = AtomicLong(-1)
    private var gesturePending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyAvailabilityChanged()
    }
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onDestroy() {
        if (instance === this) {
            instance = null
            notifyAvailabilityChanged()
        }
        super.onDestroy()
    }

    private fun execute(command: ControlCommand, reply: (ControlResult) -> Unit) {
        fun reject(reason: String) = reply(ControlResult(command.sequence, false, reason))
        ProtocolValidation.validate(command)?.let { reject(it); return }
        if (!sessionActive) { reject("session_not_active"); return }
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { reject("screen_locked"); return }
        if (command.sequence <= lastSequence.get()) { reject("replayed_command"); return }
        lastSequence.set(command.sequence)
        if (gesturePending) { reject("gesture_in_progress"); return }
        when (command) {
            is ControlCommand.Tap -> { gesture(command, command.x, command.y, command.x, command.y, 80, reply); return }
            is ControlCommand.LongPress -> { gesture(command, command.x, command.y, command.x, command.y, 650, reply); return }
            is ControlCommand.Swipe -> { gesture(command, command.fromX, command.fromY, command.toX, command.toY, command.durationMs, reply); return }
            else -> Unit
        }
        val accepted = when (command) {
            is ControlCommand.GlobalAction -> performGlobalAction(when (command.action) {
                ControlCommand.Action.BACK -> GLOBAL_ACTION_BACK
                ControlCommand.Action.HOME -> GLOBAL_ACTION_HOME
                ControlCommand.Action.RECENTS -> GLOBAL_ACTION_RECENTS
            })
            is ControlCommand.SetText -> setFocusedText(command.text)
            else -> false
        }
        reply(ControlResult(command.sequence, accepted, if (accepted) null else "action_not_supported"))
    }

    private fun gesture(command: ControlCommand, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, reply: (ControlResult) -> Unit) {
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val path = Path().apply {
            moveTo(x1 * (bounds.width() - 1), y1 * (bounds.height() - 1))
            if (x1 != x2 || y1 != y2) lineTo(x2 * (bounds.width() - 1), y2 * (bounds.height() - 1))
        }
        gesturePending = true
        var finished = false
        fun finish(ok: Boolean, reason: String?) {
            if (finished) return
            finished = true
            gesturePending = false
            reply(ControlResult(command.sequence, ok, reason))
        }
        val submitted = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { finish(true, null) }
                override fun onCancelled(gestureDescription: GestureDescription?) { finish(false, "gesture_cancelled") }
            }, mainHandler)
        if (!submitted) finish(false, "gesture_rejected")
        else mainHandler.postDelayed({ finish(false, "gesture_timeout") }, duration + 1500)
    }

    private fun setFocusedText(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable || node.isPassword) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    companion object {
        @Volatile private var instance: RemoteControlService? = null
        @Volatile private var sessionActive = false
        private val sessionGeneration = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val availabilityListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun isAvailable(): Boolean = instance != null
        fun addAvailabilityListener(listener: (Boolean) -> Unit) {
            availabilityListeners += listener
            listener(isAvailable())
        }
        fun removeAvailabilityListener(listener: (Boolean) -> Unit) { availabilityListeners -= listener }
        fun beginSession() { sessionGeneration.incrementAndGet(); sessionActive = true; instance?.lastSequence?.set(-1) }
        fun endSession() { sessionActive = false; sessionGeneration.incrementAndGet() }
        fun dispatch(command: ControlCommand, reply: (ControlResult) -> Unit) {
            val generation = sessionGeneration.get()
            mainHandler.post {
                if (!sessionActive || generation != sessionGeneration.get()) {
                    reply(ControlResult(command.sequence, false, "session_not_active"))
                    return@post
                }
                val service = instance
                if (service == null) reply(ControlResult(command.sequence, false, "accessibility_unavailable"))
                else try { service.execute(command, reply) }
                catch (_: RuntimeException) { reply(ControlResult(command.sequence, false, "action_not_supported")) }
            }
        }

        private fun notifyAvailabilityChanged() {
            val available = isAvailable()
            availabilityListeners.forEach { it(available) }
        }
    }
}
