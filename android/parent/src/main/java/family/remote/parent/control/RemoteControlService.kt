package family.remote.parent.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import family.remote.protocol.ControlCommand
import family.remote.protocol.ControlResult
import family.remote.protocol.ProtocolValidation
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

class RemoteControlService : AccessibilityService() {
    private val lastSequence = AtomicLong(-1)

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

    fun execute(command: ControlCommand): ControlResult {
        ProtocolValidation.validate(command)?.let { return ControlResult(command.sequence, false, it) }
        if (!sessionActive) return ControlResult(command.sequence, false, "session_not_active")
        if (command.sequence <= lastSequence.get() || !lastSequence.compareAndSet(lastSequence.get(), command.sequence)) {
            return ControlResult(command.sequence, false, "replayed_command")
        }
        val accepted = when (command) {
            is ControlCommand.Tap -> gesture(command.x, command.y, command.x, command.y, 1)
            is ControlCommand.LongPress -> gesture(command.x, command.y, command.x, command.y, 650)
            is ControlCommand.Swipe -> gesture(command.fromX, command.fromY, command.toX, command.toY, command.durationMs)
            is ControlCommand.GlobalAction -> performGlobalAction(when (command.action) {
                ControlCommand.Action.BACK -> GLOBAL_ACTION_BACK
                ControlCommand.Action.HOME -> GLOBAL_ACTION_HOME
                ControlCommand.Action.RECENTS -> GLOBAL_ACTION_RECENTS
            })
            is ControlCommand.SetText -> setFocusedText(command.text)
        }
        return ControlResult(command.sequence, accepted, if (accepted) null else "action_not_supported")
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1 * metrics.widthPixels, y1 * metrics.heightPixels)
            if (x1 != x2 || y1 != y2) lineTo(x2 * metrics.widthPixels, y2 * metrics.heightPixels)
        }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
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
        private val availabilityListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

        fun isAvailable(): Boolean = instance != null
        fun addAvailabilityListener(listener: (Boolean) -> Unit) {
            availabilityListeners += listener
            listener(isAvailable())
        }
        fun removeAvailabilityListener(listener: (Boolean) -> Unit) { availabilityListeners -= listener }
        fun beginSession() { sessionActive = true; instance?.lastSequence?.set(-1) }
        fun endSession() { sessionActive = false }
        fun dispatch(command: ControlCommand): ControlResult = instance?.execute(command)
            ?: ControlResult(command.sequence, false, "accessibility_unavailable")

        private fun notifyAvailabilityChanged() {
            val available = isAvailable()
            availabilityListeners.forEach { it(available) }
        }
    }
}

