package com.namalxhero.su.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

/**
 * Resolves a raw UID (all the kernel hands us) to something a human can
 * recognize, for both the Allow/Deny prompt and the granted-apps list.
 *
 * getPackagesForUid() is safe to call without QUERY_ALL_PACKAGES: that
 * restriction is about enumerating *all* installed packages, not about
 * resolving a single UID that has just contacted you - which is exactly
 * this case (the kernel told us which UID called the su client).
 */
object AppResolver {

    data class Resolved(val label: String, val packageName: String?, val icon: Drawable?)

    fun resolve(context: Context, uid: Int): Resolved {
        val pm = context.packageManager
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val pkg = pkgs?.firstOrNull()
            ?: return Resolved(label = "Unknown app (uid $uid)", packageName = null, icon = null)

        val info: ApplicationInfo? = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: pkg
        val icon = info?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        return Resolved(label = label, packageName = pkg, icon = icon)
    }
}
