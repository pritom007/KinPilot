package family.remote.parent.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import family.remote.parent.MainActivity
import family.remote.parent.control.RemoteControlService

class ScreenShareService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stopSession() }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, screenLockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.i(TAG, "onCreate")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Active support sessions", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_STOP) { stopSession(); return START_NOT_STICKY }
        if (intent?.action == ACTION_VOICE_LEAVE) { ScreenSessionCoordinator.leaveVoice(); showNotification(false); return START_NOT_STICKY }
        if (intent?.action == ACTION_VOICE_MUTE) { ScreenSessionCoordinator.setVoiceMuted(intent.getBooleanExtra(EXTRA_MUTED, true)); showNotification(true); return START_NOT_STICKY }
        if (intent?.action == ACTION_VOICE_JOIN) { if (ScreenSessionCoordinator.joinVoice()) showNotification(true); return START_NOT_STICKY }
        showNotification(false)
        if (!RemoteControlService.isAvailable()) {
            Log.w(TAG, "Accessibility service is unavailable; continuing with screen sharing only")
        }
        RemoteControlService.beginSession()
        try { ScreenSessionCoordinator.onForegroundServiceReady(intent) }
        catch (_: RuntimeException) {
            RemoteControlService.endSession()
            ScreenSessionCoordinator.stop("screen_capture_failed")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.postDelayed({ stopSession() }, 60 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun showNotification(voiceActive: Boolean) {
        val stopIntent = PendingIntent.getService(this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Your screen is being shared")
            .setContentText(if (voiceActive) "Screen and voice are active" else "Tap Stop to immediately end family support")
            .setOngoing(true).setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent).build()
        // Android 14+ requires the explicit foreground service type on startForeground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                (if (voiceActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "startForeground done")
    }

    private fun stopSession() {
        Log.i(TAG, "stopSession")
        ScreenSessionCoordinator.stop("parent_stopped")
        RemoteControlService.endSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenLockReceiver)
        RemoteControlService.endSession()
        ScreenSessionCoordinator.stop("service_destroyed")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "family.remote.STOP"
        const val ACTION_VOICE_JOIN = "family.remote.VOICE_JOIN"
        const val ACTION_VOICE_MUTE = "family.remote.VOICE_MUTE"
        const val ACTION_VOICE_LEAVE = "family.remote.VOICE_LEAVE"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_RESULT_CODE = "resultCode"; const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "support_session"; private const val NOTIFICATION_ID = 42; private const val TAG = "KinPilot/ScreenShareSvc"
    }
}
