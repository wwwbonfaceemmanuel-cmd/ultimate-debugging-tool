
package com.ultimate.debugging.ultimate_debugging_tool

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

class ApplicationInventory(private val context: Context) {

    private val packageManager: PackageManager =
        context.packageManager

    fun getInstalledApplications(): List<Map<String, Any?>> {

        val applications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return applications.map { appInfo ->

            val packageInfo = getPackageInfo(appInfo.packageName)

            val isSystemApp =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            val appName = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appInfo.packageName
            }

            mapOf(
                "appName" to appName,
                "packageName" to appInfo.packageName,
                "versionName" to packageInfo?.versionName,
                "versionCode" to packageInfo?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        it.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        it.versionCode.toLong()
                    }
                },
                "appType" to if (isSystemApp) "SYSTEM" else "USER",
                "enabled" to appInfo.enabled,
                "isSystemApp" to isSystemApp,
                "hasLaunchIntent" to (
                    packageManager.getLaunchIntentForPackage(
                        appInfo.packageName
                    ) != null
                )
            )
        }.sortedBy {
            (it["appName"] as? String)?.lowercase()
                ?: ""
        }
    }

    private fun getPackageInfo(packageName: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
    }
}
