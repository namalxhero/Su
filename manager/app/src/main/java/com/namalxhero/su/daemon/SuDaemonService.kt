package com.namalxhero.su.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
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
 * su" events, surfacing each one as a grant/deny prompt.
 *
 * Phase 3 continued: backs off instead of spinning when the device node
 * disappears, and logs (instead of silently swallowing) kernel push
 * failures from AllowlistRepository.
 *
 * FIX (manager-app completion pass, pre-device-testing): previously
 * called startActivity() directly from this background service.
 * Android 10+ blocks background activity starts from a context that
 * isn't currently visible to the user - which this daemon almost never
 * is (it's woken by a kernel event, not user interaction), so the
 * prompt could silently fail to appear on exactly the device/OEM-skin
 * combination this project is first being tested on. Replaced with the
 * standard workaround used for incoming calls/alarms: a high-importance
 * notification carrying a full-screen intent, plus a regular
 * content-intent fallback (so tapping the notification itself still
 * opens the prompt if the OS suppresses the fullscreen launch - some
 * OEMs downgrade these to heads-up only, e.g. right after the
 * notification permission was just granted).
 */
class SuDaemonService : Service() {

    private lateinit var allowlist: AllowlistRepository
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        allowlist = AllowlistRepository(this)
        createNotificationChannels()
        startForeground(DAEMON_NOTIFICATION_ID, buildForegroundNotification())
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
            showGrantNotification(uid)
        }
    }

    private fun promptIntent(uid: Int): Intent =
        Intent(this, GrantPromptActivity::class.java).apply {
            putExtra(GrantPromptActivity.EXTRA_UID, uid)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Distinct `data` per uid so concurrent requests for different
            // apps don't collapse into the same PendingIntent (the kernel
            // side's pending-request queue - su_allowlist.c - can have more
            // than one outstanding at once).
            data = Uri.parse("su-grant://$uid")
        }

    private fun showGrantNotification(uid: Int) {
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, uid, promptIntent(uid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, GRANT_CHANNEL_ID)
            .setContentTitle(getString(R.string.grant_prompt_title))
            .setContentText(getString(R.string.grant_prompt_body_unknown, uid))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // TODO: dedicated monochrome icon
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL) // closest stock category for "needs an immediate decision"
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent) // fallback: tapping the notification itself also opens the prompt
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(GRANT_NOTIFICATION_ID_BASE + (uid % 1000), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                DAEMON_CHANNEL_ID, getString(R.string.daemon_notification_title),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                GRANT_CHANNEL_ID, getString(R.string.grant_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, DAEMON_CHANNEL_ID)
            .setContentTitle(getString(R.string.daemon_notification_title))
            .setContentText(getString(R.string.daemon_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // TODO: dedicated monochrome icon
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SuDaemonService"
        private const val DAEMON_CHANNEL_ID = "su_daemon"
        private const val GRANT_CHANNEL_ID = "su_grant_request"
        private const val DAEMON_NOTIFICATION_ID = 1
        private const val GRANT_NOTIFICATION_ID_BASE = 1000
        private const val INITIAL_BACKOFF_MS = 500L
        private const val MAX_BACKOFF_MS = 10_000L
    }
}
