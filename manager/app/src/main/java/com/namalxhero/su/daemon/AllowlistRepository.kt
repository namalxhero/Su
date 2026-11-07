package com.namalxhero.su.daemon

import android.content.Context
import com.namalxhero.su.db.AllowlistEntry
import com.namalxhero.su.db.GrantType
import com.namalxhero.su.db.SuDatabase
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.util.AppInfoResolver
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for *persistent* allowlist policy (Phase 1 design:
 * "manager app is the source of truth, kernel cache is just a fast
 * runtime mirror"). Backed by Room (Phase 3 continued: moved off
 * SharedPreferences now that the schema carries label, package name,
 * grant type and timestamp - more than a bare {uid -> granted} map).
 *
 * All methods are suspend except [observeGranted] (a Flow, safe to
 * collect from Compose) - callers must invoke from a coroutine scope,
 * never the main thread directly.
 */
class AllowlistRepository(context: Context) {

    private val dao = SuDatabase.get(context).allowlistDao()
    private val resolver = AppInfoResolver(context)

    /** Live view of the allowlist, newest grant first - safe to collect in Compose. */
    fun observeGranted(): Flow<List<AllowlistEntry>> = dao.observeAll()

    suspend fun grant(uid: Int, type: GrantType = GrantType.PERMANENT) {
        val resolved = resolver.resolve(uid)
        dao.upsert(
            AllowlistEntry(
                uid = uid,
                packageName = resolved.packageName,
                label = resolved.label,
                grantType = type,
                grantedAtMillis = System.currentTimeMillis(),
            )
        )
        pushToKernel(uid, type)
    }

    suspend fun revoke(uid: Int) {
        dao.deleteByUid(uid)
        SuNative.revokeUid(uid)
    }

    suspend fun isGranted(uid: Int): Boolean = dao.getByUid(uid) != null

    suspend fun grantedUids(): List<Int> = dao.getAll().map { it.uid }

    /** Called once at boot (SuDaemonService.onCreate) to re-sync the
     * kernel's in-memory cache, which is wiped on every module reload. */
    suspend fun pushAllToKernel() {
        dao.getAll().forEach { entry -> pushToKernel(entry.uid, entry.grantType) }
    }

    /** Result of a kernel push, surfaced to callers instead of being swallowed
     * (Phase 3 continued fix: the daemon now logs + can notify on failure). */
    sealed interface PushResult {
        data object Ok : PushResult
        data class Failed(val uid: Int, val errno: Int) : PushResult
    }

    private fun pushToKernel(uid: Int, type: GrantType): PushResult {
        val flags = if (type == GrantType.SESSION) FLAG_SESSION else FLAG_PERMANENT
        val ret = SuNative.grantUid(uid, flags)
        return if (ret < 0) PushResult.Failed(uid, ret) else PushResult.Ok
    }

    companion object {
        private const val FLAG_PERMANENT = 0x1 // mirrors SU_GRANT_PERMANENT
        private const val FLAG_SESSION = 0x2   // mirrors SU_GRANT_SESSION
    }
}
