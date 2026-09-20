package com.example.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.AppRepository
import com.example.model.JobState
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

class TakeoutRestoreWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    companion object {
        const val CHANNEL_ID = "takeout_restore_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_ZIP_URIS = "key_zip_uris"
        const val KEY_DEST_URI = "key_dest_uri"
        const val KEY_RECREATE_FOLDERS = "key_recreate_folders"
        const val KEY_TIMEZONE = "key_timezone"
    }

    private val repository = AppRepository(AppDatabase.getInstance(context))
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification("Starting Takeout Restorer...", 0, 100, "")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val zipUriStrings = inputData.getStringArray(KEY_ZIP_URIS) ?: return Result.failure()
        val destUriString = inputData.getString(KEY_DEST_URI) ?: return Result.failure()
        val recreateFolders = inputData.getBoolean(KEY_RECREATE_FOLDERS, true)
        val timezoneId = inputData.getString(KEY_TIMEZONE) ?: ZoneId.systemDefault().id

        val zipUris = zipUriStrings.map { Uri.parse(it) }
        val destUri = Uri.parse(destUriString)
        val zoneId = try {
            ZoneId.of(timezoneId)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }

        // Set initial foreground
        setForeground(getForegroundInfo())

        return try {
            val processor = ZipProcessor(
                context = applicationContext,
                repository = repository,
                zipUris = zipUris,
                destinationTreeUri = destUri,
                recreateFolders = recreateFolders,
                zoneId = zoneId,
                isCancelled = { isStopped },
                onProgressUpdate = { state ->
                    updateNotification(state)
                }
            )

            processor.execute()

            // Completion notification
            showCompletionNotification("Restoration complete", "All metadata applied to media files.")
            Result.success()
        } catch (e: CancellationException) {
            // Handled for Android 15 dataSync timeout or user cancellation
            notificationManager.cancel(NOTIFICATION_ID)
            val state = repository.getJobState()
            if (state != null) {
                repository.updateJobState(state.copy(phase = "STOPPED"))
            }
            repository.insertLog("WARN", "Job stopped/timed out. Progress saved for clean resume.")
            Result.retry()
        } catch (e: Exception) {
            notificationManager.cancel(NOTIFICATION_ID)
            val state = repository.getJobState()
            if (state != null) {
                repository.updateJobState(state.copy(phase = "FAILED", errorMessage = e.message))
            }
            repository.insertLog("ERROR", "Job failed: ${e.message}")
            Result.failure()
        }
    }

    private fun updateNotification(state: JobState) {
        val total = if (state.totalBytes > 0) state.totalBytes else 1L
        val progressPercent = ((state.bytesRead.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)

        val title = if (state.phase == "PASS_1") {
            "Takeout Restorer: Caching Metadata (Pass 1/2)"
        } else {
            "Takeout Restorer: Restoring Files (Pass 2/2)"
        }

        val text = if (state.currentFile.isNotEmpty()) {
            "File: ${state.currentFile} (${state.filesProcessed} processed)"
        } else {
            "${state.filesProcessed} files processed"
        }

        val notification = createNotification(title, progressPercent, 100, text)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(
        title: String,
        progress: Int,
        maxProgress: Int,
        subText: String
    ): Notification {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setProgress(maxProgress, progress, false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun showCompletionNotification(title: String, message: String) {
        val openAppIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Takeout Restoration Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of Takeout photo/video restoration"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
