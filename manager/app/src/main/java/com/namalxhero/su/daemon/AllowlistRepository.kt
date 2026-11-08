package com.namalxhero.su.daemon

import android.content.Context
import com.namalxhero.su.native.SuNative

/**
 * Source of truth for *persistent* allowlist policy (Phase 1 design:
 * "manager app is the source of truth, kernel cache is just a fast
 * runtime mirror"). Backed by SharedPreferences for this skeleton -
 * TODO(Phase 3 continued): move to Room/SQLite once the schema needs
 * more than {uid -> granted}, e.g. per-app labels, grant timestamps,
 * session-vs-permanent distinction surfaced in the UI.
 */
class AllowlistRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun grant(uid: Int) {
        prefs.edit()
            .putBoolean(key(uid), true)
            .putLong(tsKey(uid), System.currentTimeMillis())
            .apply()
        pushToKernel(uid, GRANT_PERMANENT)
    }

    fun revoke(uid: Int) {
        prefs.edit().remove(key(uid)).remove(tsKey(uid)).apply()
        SuNative.revokeUid(uid)
    }

    fun isGranted(uid: Int): Boolean = prefs.getBoolean(key(uid), false)

    fun grantedUids(): List<Int> =
        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { it.removePrefix(KEY_PREFIX).toIntOrNull() }

    /** When each currently-granted UID was granted (epoch millis; 0 if
     * unknown, e.g. a grant written before this field existed). */
    fun grantedAt(uid: Int): Long = prefs.getLong(tsKey(uid), 0L)

    /** Called once at boot (SuDaemonService.onCreate) to re-sync the
     * kernel's in-memory cache, which is wiped on every module reload. */
    fun pushAllToKernel() {
        grantedUids().forEach { uid -> pushToKernel(uid, GRANT_PERMANENT) }
    }

    private fun pushToKernel(uid: Int, flags: Int) {
        val ret = SuNative.grantUid(uid, flags)
        if (ret < 0) {
            // TODO(Phase 3 continued): surface this failure somewhere
            // visible (notification?) instead of swallowing it - a
            // failed kernel push means the UI *thinks* the app is
            // granted but the kernel disagrees.
        }
    }

    private fun key(uid: Int) = "$KEY_PREFIX$uid"
    private fun tsKey(uid: Int) = "$TS_PREFIX$uid"

    companion object {
        private const val PREFS_NAME = "su_allowlist"
        private const val KEY_PREFIX = "uid_"
        private const val TS_PREFIX = "uid_ts_"
        private const val GRANT_PERMANENT = 0x1 // mirrors SU_GRANT_PERMANENT
    }
}
