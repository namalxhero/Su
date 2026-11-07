package com.namalxhero.su.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.ui.GrantPromptActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 */
class SuDaemonService : Service() {

    private lateinit var allowlist: AllowlistRepository
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        allowlist = AllowlistRepository(this)
        allowlist.pushAllToKernel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startListenLoop()
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

    private fun startListenLoop() {
        scope.launch {
            while (true) {
                val uid = SuNative.waitForPendingRequest()
                if (uid < 0) {
                    // TODO(Phase 3 continued): back off + retry instead of
                    // a tight loop if the device node disappears (module
                    // unloaded/crashed) - right now this will spin.
                    continue
                }
                if (allowlist.isGranted(uid)) {
                    // Already granted since the request was queued (e.g.
                    // user approved it a moment ago) - nothing to show.
                    continue
                }
                launchGrantPrompt(uid)
            }
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
                CHANNEL_ID, "Su daemon", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Su is active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock) // TODO: real icon
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "su_daemon"
        private const val NOTIFICATION_ID = 1
    }
}
