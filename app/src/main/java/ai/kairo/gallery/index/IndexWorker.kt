package ai.kairo.gallery.index

import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ai.kairo.gallery.KairoApp
import java.time.Duration

/** Runs the indexing pipeline in the background. */
class IndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try {
            setForeground(foregroundInfo())
        } catch (t: Throwable) {
            // Android may refuse a foreground start from the background; indexing still runs.
            Log.w("KairoWorker", "Foreground not allowed, continuing", t)
        }
        val force = inputData.getBoolean(IndexScheduler.KEY_FORCE, false)
        return try {
            Indexer.run(applicationContext, force)
            Result.success()
        } catch (t: Throwable) {
            Log.e("KairoWorker", "Indexing run failed", t)
            Result.failure()
        } finally {
            // A content trigger fires only once — register the next one.
            if (tags.contains(IndexScheduler.TAG_WATCH)) {
                IndexScheduler.scheduleWatch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, KairoApp.CHANNEL_INDEX)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Kairo is indexing your gallery")
            .setContentText("Reading new images on-device")
            .setOngoing(true)
            .build()
        return ForegroundInfo(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

object IndexScheduler {
    const val KEY_FORCE = "force"
    const val TAG_WATCH = "kairo-watch-tag"
    private const val WORK_WATCH = "kairo-watch"
    private const val WORK_NOW = "kairo-now"

    /** Wakes the worker whenever any image is added/changed in MediaStore. */
    fun scheduleWatch(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(2))
            .setTriggerContentMaxDelay(Duration.ofSeconds(10))
            .build()
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setConstraints(constraints)
            .addTag(TAG_WATCH)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_WATCH, policy, request)
    }

    /** Stops any queued or running indexing (used before wiping the index). */
    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(WORK_NOW)
        wm.cancelUniqueWork(WORK_WATCH)
    }

    /** Index right now (button / app start). */
    fun runNow(ctx: Context, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<IndexWorker>()
            .setInputData(workDataOf(KEY_FORCE to force))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }
}
