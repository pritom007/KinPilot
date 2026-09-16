package family.remote.parent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return try {
            val release = AppUpdates.latest() ?: return Result.success()
            val notifications = NotificationManagerCompat.from(applicationContext)
            if (!notifications.areNotificationsEnabled()) return Result.success()
            val preferences = applicationContext.getSharedPreferences("kinpilot", Context.MODE_PRIVATE)
            if (preferences.getString("notifiedVersion", null) == release.version) return Result.success()
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(applicationContext, 40,
                Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(40, NotificationCompat.Builder(applicationContext, "updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("KinPilot ${release.version} is available")
                .setContentText("Tap to download the latest family support update.")
                .setContentIntent(open).setAutoCancel(true).build())
            preferences.edit().putString("notifiedVersion", release.version).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("kinpilot-updates", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
        }
    }
}
