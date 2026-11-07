package com.namalxhero.su.native

/**
 * Thin JNI bridge to the native layer (su_native.cpp), which does the
 * actual ioctl()/read() calls against /dev/su_ctl (see Phase 2's
 * kernel/include/su_uapi.h for the wire format these mirror).
 *
 * Kept deliberately small: no business logic here, just the raw
 * primitives. Higher-level logic (what the UI shows, what the daemon
 * does with a pending request) lives in SuDaemonService / the
 * ViewModel, not here.
 */
object SuNative {

    init {
        System.loadLibrary("su_native")
    }

    /** Mirrors SU_IOC_UID_GRANT. flags: see SU_GRANT_* in su_uapi.h. */
    external fun grantUid(uid: Int, flags: Int): Int

    /** Mirrors SU_IOC_UID_REVOKE. */
    external fun revokeUid(uid: Int): Int

    /** Mirrors SU_IOC_UID_QUERY. Returns the flags bitmask, or -1 on error. */
    external fun queryUid(uid: Int): Int

    /** Mirrors SU_IOC_GET_VERSION. Returns [apiVersion, maxAllowlist]. */
    external fun getVersionInfo(): IntArray

    /**
     * Blocking call - mirrors read() on /dev/su_ctl. Returns the UID of
     * the next "unknown UID requested su" event, blocking the calling
     * thread until one arrives. Callers MUST invoke this from a
     * dedicated background thread/coroutine, never the main thread.
     *
     * Returns -1 if the read was interrupted (e.g. module unloaded).
     */
    external fun waitForPendingRequest(): Int
}
