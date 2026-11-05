
package com.ultimate.debugging.ultimate_debugging_tool

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class ApplicationInventory(private val context: Context) {

    private val packageManager: PackageManager =
        context.packageManager

    fun getInstalledApplications(): List<Map<String, Any?>> {

        val applications =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

            val installSource = getInstallSource(appInfo.packageName)
            val signingInfo = getSigningInformation(appInfo.packageName)

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
                ),

                // Application diagnostic information.
                "uid" to appInfo.uid,
                "targetSdkVersion" to appInfo.targetSdkVersion,
                "firstInstallTime" to packageInfo?.firstInstallTime,
                "lastUpdateTime" to packageInfo?.lastUpdateTime,
                "installSource" to installSource,
                "packageInfoAvailable" to (packageInfo != null),

                // APK signing information.
                "signingInfoAvailable" to signingInfo.available,
                "hasMultipleSigners" to signingInfo.hasMultipleSigners,
                "signerCount" to signingInfo.fingerprints.size,
                "certificateSha256" to signingInfo.fingerprints
            )
        }.sortedBy {
            (it["appName"] as? String)?.lowercase() ?: ""
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

    private fun getInstallSource(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager
                    .getInstallSourceInfo(packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class SigningInformation(
        val available: Boolean,
        val hasMultipleSigners: Boolean,
        val fingerprints: List<String>
    )

    private fun getSigningInformation(
        packageName: String
    ): SigningInformation {

        return try {
            val packageInfo = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                    )
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
            }

            val signatures = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                val info = packageInfo.signingInfo
                    ?: return SigningInformation(false, false, emptyList())

                if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
                    ?: return SigningInformation(false, false, emptyList())
            }

            val fingerprints = signatures.map { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())

                digest.joinToString("") { byte ->
                    "%02X".format(byte.toInt() and 0xFF)
                }
            }.distinct()

            SigningInformation(
                available = fingerprints.isNotEmpty(),
                hasMultipleSigners =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    packageInfo.signingInfo?.hasMultipleSigners() == true,
                fingerprints = fingerprints
            )
        } catch (_: Exception) {
            SigningInformation(false, false, emptyList())
        }
    }
}
