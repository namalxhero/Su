package com.namalxhero.su.util

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * Resolves a raw Linux UID to something a human can recognise: the
 * owning app's label and icon, via PackageManager#getPackagesForUid.
 *
 * A UID can be shared by several packages (android:sharedUserId) - we
 * just take the first result, which is what every root manager (Magisk,
 * KernelSU) does for display purposes, since the allowlist grant itself
 * is still keyed by UID, not package name.
 */
data class ResolvedApp(
    val uid: Int,
    val label: String,
    val packageName: String?,
    val icon: Drawable?,
)

class AppInfoResolver(context: Context) {

    private val pm = context.applicationContext.packageManager

    fun resolve(uid: Int): ResolvedApp {
        val packages = pm.getPackagesForUid(uid)
        val packageName = packages?.firstOrNull()

        if (packageName == null) {
            return ResolvedApp(uid = uid, label = "uid $uid", packageName = null, icon = null)
        }

        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            ResolvedApp(
                uid = uid,
                label = pm.getApplicationLabel(appInfo).toString(),
                packageName = packageName,
                icon = pm.getApplicationIcon(appInfo),
            )
        } catch (e: PackageManager.NameNotFoundException) {
            ResolvedApp(uid = uid, label = packageName, packageName = packageName, icon = null)
        }
    }
}
