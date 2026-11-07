package com.namalxhero.su.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.namalxhero.su.R
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.ui.GrantPromptActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 1 design, implemented: boots with the device, pushes the
 * persisted allowlist into the kernel cache once, then sits in a
 * blocking-read loop on /dev/su_ctl waiting for "unknown UID requested
 * su" events, launching the Allow/Deny prompt for each one.
 *
 * Deliberately a single always-on background thread rather than
 * WorkManager/JobScheduler - the blocking kernel read needs a thread
 * that is *always* parked waiting, not one that gets scheduled
 * periodically.
 *
 * Phase 3 continued: backs off instead of spinning when the device node
 * disappears, and logs (instead of silently swallowing) kernel push
 * failures from AllowlistRepository.
 */
class SuDaemonService : Service() {

    private lateinit var allowlist: AllowlistRepository
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        allowlist = AllowlistRepository(this)
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        scope.launch {
            allowlist.pushAllToKernel()
            startListenLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: the daemon should come back if the system kills it.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private suspend fun startListenLoop() {
        var backoffMillis = INITIAL_BACKOFF_MS
        while (true) {
            val uid = SuNative.waitForPendingRequest()
            if (uid < 0) {
                // Device node is gone (module unloaded/crashed) or the read
                // was interrupted - back off with a cap instead of spinning.
                Log.w(TAG, "waitForPendingRequest() failed, backing off ${backoffMillis}ms")
                delay(backoffMillis)
                backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            backoffMillis = INITIAL_BACKOFF_MS

            if (allowlist.isGranted(uid)) {
                // Already granted since the request was queued (e.g. user
                // approved it a moment ago) - nothing to show.
                continue
            }
            launchGrantPrompt(uid)
        }
    }

    private fun launchGrantPrompt(uid: Int) {
        val intent = Intent(this, GrantPromptActivity::class.java).apply {
            putExtra(GrantPromptActivity.EXTRA_UID, uid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.daemon_notification_title), NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.daemon_notification_title))
            .setContentText(getString(R.string.daemon_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // TODO: dedicated monochrome icon
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SuDaemonService"
        private const val CHANNEL_ID = "su_daemon"
        private const val NOTIFICATION_ID = 1
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 10_000L
    }
}
