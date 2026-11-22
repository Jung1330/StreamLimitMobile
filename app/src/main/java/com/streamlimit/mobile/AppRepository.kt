package com.streamlimit.mobile

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val uid: Int
)

object AppRepository {

    @Volatile
    private var cachedApps: List<InstalledAppItem>? = null
    private val iconCache = HashMap<String, Drawable>()

    fun getInternetApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppItem> {
        val current = cachedApps
        if (!forceRefresh && current != null) {
            return current
        }

        val pm = context.packageManager
        val selfPkg = context.packageName
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (_: Exception) {
            emptyList<PackageInfo>()
        }

        val list = ArrayList<InstalledAppItem>(packages.size)
        for (pkgInfo in packages) {
            val pkgName = pkgInfo.packageName ?: continue
            if (pkgName == selfPkg) continue
            val appInfo = pkgInfo.applicationInfo ?: continue

            val requestedPerms = pkgInfo.requestedPermissions
            val hasInternet = requestedPerms?.any { it == Manifest.permission.INTERNET } == true
            if (!hasInternet) continue

            val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

            val label = try {
                pm.getApplicationLabel(appInfo).toString().trim().ifEmpty { pkgName }
            } catch (_: Exception) {
                pkgName
            }

            list.add(
                InstalledAppItem(
                    packageName = pkgName,
                    appName = label,
                    isSystemApp = isSys,
                    uid = appInfo.uid
                )
            )
        }

        list.sortWith(compareBy<InstalledAppItem> { it.isSystemApp }.thenBy { it.appName.lowercase() })
        cachedApps = list
        return list
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        synchronized(iconCache) {
            iconCache[packageName]?.let { return it }
        }
        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            synchronized(iconCache) {
                iconCache[packageName] = icon
            }
            icon
        } catch (_: Exception) {
            null
        }
    }
}
