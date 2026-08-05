package family.remote.parent.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import family.remote.parent.MainActivity
import family.remote.parent.control.RemoteControlService

class ScreenShareService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Active support sessions", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSession(); return START_NOT_STICKY }
        val stopIntent = PendingIntent.getService(this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Your screen is being shared")
            .setContentText("Tap Stop to immediately end family support")
            .setOngoing(true).setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent).build()
        startForeground(NOTIFICATION_ID, notification)
        RemoteControlService.beginSession()
        ScreenSessionCoordinator.onForegroundServiceReady(intent)
        return START_NOT_STICKY
    }

    private fun stopSession() {
        ScreenSessionCoordinator.stop("parent_stopped")
        RemoteControlService.endSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { RemoteControlService.endSession(); ScreenSessionCoordinator.stop("service_destroyed"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val ACTION_STOP = "family.remote.STOP"; const val EXTRA_RESULT_CODE = "resultCode"; const val EXTRA_RESULT_DATA = "resultData"; private const val CHANNEL = "support_session"; private const val NOTIFICATION_ID = 42 }
}

