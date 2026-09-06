package family.remote.protocol

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** Handle input on the renderer itself, without a Compose overlay over SurfaceView. */
object RemoteTouchInput {
    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, dimensions: () -> Pair<Int, Int>, enabled: () -> Boolean,
               next: () -> Long, send: (ControlCommand) -> Boolean) {
        var start: Pair<Float, Float>? = null
        var startX = 0f
        var startY = 0f
        var moved = false
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            val (width, height) = dimensions()
            val point = ScreenCoordinates.normalize(event.x, event.y, view.width, view.height, width, height)
            if (!enabled()) { start = null; return@setOnTouchListener false }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = point
                    startX = event.x; startY = event.y; moved = false
                    if (point != null) view.parent?.requestDisallowInterceptTouchEvent(true)
                    point != null
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.hypot(event.x - startX, event.y - startY) > slop) moved = true
                    start != null
                }
                MotionEvent.ACTION_UP -> {
                    val from = start
                    start = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (from != null && point != null) {
                        val duration = event.eventTime - event.downTime
                        val dragged = moved || kotlin.math.hypot(event.x - startX, event.y - startY) > slop
                        send(when {
                            dragged -> ControlCommand.Swipe(next(), from.first, from.second, point.first, point.second, duration.coerceIn(50L, 2000L))
                            duration >= ViewConfiguration.getLongPressTimeout() -> ControlCommand.LongPress(next(), from.first, from.second)
                            else -> ControlCommand.Tap(next(), from.first, from.second)
                        })
                    }
                    from != null
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    start = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> start != null
            }
        }
    }
}
