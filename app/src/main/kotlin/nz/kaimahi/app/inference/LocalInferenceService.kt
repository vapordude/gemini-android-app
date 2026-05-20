package nz.kaimahi.app.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Anchors the on-device LLM in the foreground process tier so Android's
 * `lmkd` doesn't reap us while a multi-GB mmap is resident.
 *
 * Lifecycle:
 *  - `MainActivity` calls `ContextCompat.startForegroundService` and
 *    binds whenever `InferenceMode` flips to `LOCAL_AGENT`.
 *  - `startForeground` runs in `onCreate` so we hit Android's 5 s
 *    "promote within" window the moment the system starts the service.
 *  - On `unbind` from the activity (mode flips off LOCAL_AGENT, or the
 *    process is going away), `MainActivity` calls `stopService` which
 *    drops the foreground tag.
 *
 * The Rust mmap + JNI session live in `RustInferenceEngine`; this
 * service does NOT own them. It exists purely to keep our `oom_adj`
 * priority above background apps while a local model is loaded.
 */
class LocalInferenceService : Service() {

    inner class LocalBinder : Binder() {
        val service: LocalInferenceService get() = this@LocalInferenceService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        // CRITICAL: must call startForeground within ~5 s of
        // startForegroundService() or the system kills us with a
        // ForegroundServiceDidNotStartInTimeException. Doing it in
        // onCreate (which runs synchronously from the framework start
        // path) is the safest guarantee.
        //
        // Every call here can fail in surprising ways across OEMs and
        // Android versions:
        //   - getSystemService(NotificationManager) can return null on
        //     a hardened ROM, leaving the channel uncreated
        //   - startForeground can throw SecurityException if
        //     POST_NOTIFICATIONS wasn't granted (API 33+)
        //   - startForeground can throw ForegroundServiceTypeMismatch
        //     if the runtime type doesn't match the manifest declaration
        // The activity-side launch is best-effort already (it catches
        // ForegroundServiceStartNotAllowedException), so we follow
        // suit here: catch + log rather than letting a throw bubble
        // up into a system-level kill of the entire process.
        runCatching {
            ensureChannel()
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: declare specialUse for honesty. The manifest
                // already advertises the subtype "on_device_llm_inference".
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29-33: SPECIAL_USE doesn't exist yet; DATA_SYNC is
                // the closest legacy bucket Android offers for a long-
                // running CPU job without a mic/camera/location grab.
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        }.onFailure {
            android.util.Log.w(TAG, "startForeground failed; falling back to plain service", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Don't restart on system kill — the activity rebinds when the
        // user re-enters LOCAL_AGENT mode and triggers a fresh load.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local inference",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "On-device LLM is active. Tap to return to chat."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Kaimahi — local model active")
            .setContentText("Keeping the on-device LLM resident.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "LocalInferenceService"
        const val CHANNEL_ID = "local_inference"
        const val NOTIFICATION_ID = 0x4B41 // 'KA'
    }
}
