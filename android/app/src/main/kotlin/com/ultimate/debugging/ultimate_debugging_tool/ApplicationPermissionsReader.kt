package com.ultimate.debugging.ultimate_debugging_tool

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build

/** Reads the permissions of an app installed on this phone. */
class ApplicationPermissionsReader(private val context: Context) {

    private val packageManager: PackageManager
        get() = context.packageManager

    fun getApplicationPermissions(
        packageName: String
    ): List<Map<String, String>> {

        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_PERMISSIONS.toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS
                )
            }

        val requestedPermissions =
            packageInfo.requestedPermissions ?: return emptyList()

        val permissionFlags =
            packageInfo.requestedPermissionsFlags

        return requestedPermissions.mapIndexed { index, permission ->

            val granted =
                permissionFlags != null &&
                    index < permissionFlags.size &&
                    (permissionFlags[index] and
                        android.content.pm.PackageInfo
                            .REQUESTED_PERMISSION_GRANTED) != 0

            val special = isSpecialPermission(permission)

            val status = when {
                special -> "unknown"
                granted -> "granted"
                else -> "denied"
            }

            val sensitivity = getPermissionSensitivity(permission)

            val explanation = when {
                special ->
                    "This permission may require special access in Android settings. Its actual access state has not been verified."

                sensitivity == "critical" ->
                    "This permission may provide access to highly sensitive messages, calls or communication information."

                sensitivity == "sensitive" ->
                    "This permission may provide access to personal information or sensitive device features."

                else ->
                    "This permission is declared by the application. Review its purpose and whether it is necessary."
            }

            val recommendation = when {
                special ->
                    "Inspect the relevant Android special-access settings before making a decision."

                status == "denied" ->
                    "Check whether the application requires this permission to function correctly."

                sensitivity == "critical" ->
                    "Verify that this permission is necessary and that the application is trusted."

                sensitivity == "sensitive" ->
                    "Confirm that this access is appropriate for the application's purpose."

                else ->
                    "No immediate action is indicated. Review if investigating an application problem."
            }

            mapOf(
                "permissionName" to permission,
                "status" to status,
                "sensitivity" to sensitivity,
                "explanation" to explanation,
                "recommendation" to recommendation
            )
        }
    }

    private fun isSpecialPermission(
        permission: String
    ): Boolean {

        val specialPermissions = setOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SCHEDULE_EXACT_ALARM"
        )

        return permission in specialPermissions
    }

    private fun getPermissionSensitivity(
        permission: String
    ): String {

        val criticalPermissions = setOf(
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS"
        )

        if (permission in criticalPermissions) {
            return "critical"
        }

        if (isSpecialPermission(permission)) {
            return "special"
        }

        val sensitivePermissions = setOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_PHONE_NUMBERS",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE"
        )

        if (
            permission in sensitivePermissions ||
            permission.startsWith("android.permission.READ_MEDIA_")
        ) {
            return "sensitive"
        }

        return "normal"
    }

}
