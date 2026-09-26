package ai.kairo.gallery

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ai.kairo.gallery.index.IndexScheduler
import ai.kairo.gallery.llm.LlmTuning

class KairoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INDEX,
                "Gallery indexing",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Debug A/B overrides (never written in normal use).
        LlmTuning.loadOverrides(this)
        // Make sure the model folder exists so `adb push` has a target.
        getExternalFilesDir(null)?.mkdirs()
        // Wake up whenever the gallery changes.
        IndexScheduler.scheduleWatch(this)
    }

    companion object {
        const val CHANNEL_INDEX = "kairo_index"
    }
}
