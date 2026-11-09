package com.namalxhero.su.daemon

import android.content.Context

/**
 * Whether [SuDaemonService] should be started automatically from
 * [BootReceiver] on BOOT_COMPLETED. Plain SharedPreferences rather
 * than DataStore (not a project dependency yet, see app/build.gradle.kts)
 * - BootReceiver.onReceive() also needs this read synchronously, before
 * doing anything async, so a blocking SharedPreferences read is the
 * simpler fit here anyway.
 *
 * Defaults to enabled: a fresh install should auto-start the daemon
 * without the user having to find and flip a setting first - this is
 * the opt-out, not opt-in.
 */
object AutoStartPrefs {
    private const val PREFS_NAME = "su_daemon_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .apply()
    }
}
