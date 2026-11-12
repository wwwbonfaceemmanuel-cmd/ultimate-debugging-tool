package com.ultimate.debugging.ultimate_debugging_tool

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Copies the APK files of an app installed on this phone into private
 * storage, verifies the copies and shares them.
 */
class ApkExporter(
    private val activity: Activity,
    private val isDestroyed: () -> Boolean
) {

    companion object {
        private const val TAG = "UltimateDebuggingTool"
        private const val EXPORT_DIRECTORY = "apk_exports"
        private const val MAX_EXPORT_AGE_MS = 24L * 60 * 60 * 1000
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val activityDestroyed: Boolean
        get() = isDestroyed()

    private val filesDir: File
        get() = activity.filesDir

    private val packageManager: PackageManager
        get() = activity.packageManager

    private val contentResolver: ContentResolver
        get() = activity.contentResolver

    /**
     * Deletes backups older than one day so exported APK files do not
     * silently fill the technician's phone.
     */
    private fun removeOldExports() {
        try {
            val cutoff = System.currentTimeMillis() - MAX_EXPORT_AGE_MS

            File(filesDir, EXPORT_DIRECTORY)
                .listFiles()
                ?.filter { it.lastModified() < cutoff }
                ?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove old APK backups", e)
        }
    }

    private fun calculateSha256(
        file: File
    ): String {

        val digest = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)

            while (true) {
                val bytesRead = input.read(buffer)

                if (bytesRead == -1) {
                    break
                }

                digest.update(buffer, 0, bytesRead)
            }
        }

        return digest.digest().joinToString("") {
            "%02X".format(it)
        }
    }

    fun exportApplicationApk(
        packageName: String,
        result: MethodChannel.Result
    ) {

        Thread({

            var backupDirectory: File? = null

            try {
                val appInfo = packageManager.getApplicationInfo(
                    packageName,
                    0
                )

                val sourceFiles = mutableListOf<File>()

                sourceFiles.add(File(appInfo.sourceDir))

                appInfo.splitSourceDirs?.forEach { path ->
                    sourceFiles.add(File(path))
                }

                val sourcePaths = sourceFiles.map {
                    it.canonicalPath
                }

                if (sourcePaths.toSet().size != sourcePaths.size) {
                    throw IllegalStateException(
                        "Duplicate APK source files were detected."
                    )
                }

                if (
                    sourceFiles.isEmpty() ||
                    sourceFiles.any {
                        !it.exists() || !it.isFile || !it.canRead()
                    }
                ) {
                    throw IllegalStateException(
                        "One or more APK source files are unavailable."
                    )
                }

                val safePackageName = packageName.replace(
                    Regex("[^A-Za-z0-9._-]"),
                    "_"
                )

                removeOldExports()

                val directory = File(
                    filesDir,
                    "apk_exports/$safePackageName-${System.currentTimeMillis()}"
                )

                if (!directory.mkdirs() && !directory.isDirectory) {
                    throw IllegalStateException(
                        "Unable to create the APK backup directory."
                    )
                }

                backupDirectory = directory

                val backupFiles = mutableListOf<Map<String, Any>>()

                sourceFiles.forEachIndexed { index, sourceFile ->

                    val fileName = if (index == 0) {
                        "base.apk"
                    } else {
                        "split_${index}.apk"
                    }

                    val destinationFile = File(
                        directory,
                        fileName
                    )

                    val originalHash = calculateSha256(sourceFile)

                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }

                    if (sourceFile.length() != destinationFile.length()) {
                        throw IllegalStateException(
                            "File size verification failed for $fileName"
                        )
                    }

                    val backupHash = calculateSha256(destinationFile)

                    if (originalHash != backupHash) {
                        throw IllegalStateException(
                            "SHA-256 verification failed for $fileName"
                        )
                    }

                    backupFiles.add(
                        mapOf(
                            "fileName" to fileName,
                            "sizeBytes" to destinationFile.length(),
                            "sha256" to backupHash,
                            "verified" to true
                        )
                    )
                }

                mainHandler.post {

                    if (activityDestroyed) {
                        return@post
                    }

                    result.success(
                        mapOf(
                            "success" to true,
                            "packageName" to packageName,
                            "backupDirectory" to directory.absolutePath,
                            "fileCount" to backupFiles.size,
                            "files" to backupFiles,
                            "integrityVerified" to true
                        )
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "APK backup failed for $packageName",
                    e
                )

                backupDirectory?.let { directory ->
                    try {
                        directory.deleteRecursively()
                    } catch (cleanupError: Exception) {
                        Log.e(
                            TAG,
                            "Unable to clean up incomplete APK backup",
                            cleanupError
                        )
                    }
                }

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.error(
                            "APK_EXPORT_ERROR",
                            e.message
                                ?: "Unable to export application APK.",
                            null
                        )
                    }
                }
            }

        }, "APK-Backup-Worker").start()
    }

    fun shareApplicationApkBackup(
        backupDirectoryPath: String,
        fileMetadata: List<*>,
        result: MethodChannel.Result
    ) {

        Thread({

            try {
                val backupRoot = File(
                    filesDir,
                    "apk_exports"
                ).canonicalFile

                val backupDirectory =
                    File(backupDirectoryPath).canonicalFile

                if (
                    backupDirectory.parentFile != backupRoot ||
                    !backupDirectory.isDirectory
                ) {
                    throw SecurityException(
                        "The requested backup directory is not permitted."
                    )
                }

                val validatedFiles = mutableListOf<File>()
                val seenNames = mutableSetOf<String>()

                fileMetadata.forEach { item ->

                    val metadata = item as? Map<*, *>
                        ?: throw IllegalArgumentException(
                            "Invalid APK backup metadata."
                        )

                    val fileName = metadata["fileName"] as? String
                        ?: throw IllegalArgumentException(
                            "APK filename is missing."
                        )

                    val expectedHash = metadata["sha256"] as? String
                        ?: throw IllegalArgumentException(
                            "APK SHA-256 fingerprint is missing."
                        )

                    val verified =
                        metadata["verified"] as? Boolean ?: false

                    val expectedSize =
                        (metadata["sizeBytes"] as? Number)?.toLong()
                            ?: throw IllegalArgumentException(
                                "APK file size is missing."
                            )

                    if (!verified) {
                        throw SecurityException(
                            "An APK file has not passed backup verification."
                        )
                    }

                    if (
                        !fileName.matches(
                            Regex("""(?:base|split_[0-9]+)\.apk""")
                        )
                    ) {
                        throw SecurityException(
                            "An invalid APK filename was supplied."
                        )
                    }

                    if (!seenNames.add(fileName)) {
                        throw SecurityException(
                            "Duplicate APK filename detected."
                        )
                    }

                    if (!expectedHash.matches(Regex("[A-Fa-f0-9]{64}"))) {
                        throw SecurityException(
                            "Invalid SHA-256 fingerprint."
                        )
                    }

                    val apkFile = File(
                        backupDirectory,
                        fileName
                    ).canonicalFile

                    if (
                        apkFile.parentFile != backupDirectory ||
                        !apkFile.exists() ||
                        !apkFile.isFile ||
                        !apkFile.canRead()
                    ) {
                        throw SecurityException(
                            "An APK backup file is unavailable."
                        )
                    }

                    if (apkFile.length() != expectedSize) {
                        throw SecurityException(
                            "APK size verification failed for $fileName."
                        )
                    }

                    val actualHash = calculateSha256(apkFile)

                    if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                        throw SecurityException(
                            "APK integrity verification failed for $fileName."
                        )
                    }

                    validatedFiles.add(apkFile)
                }

                if (
                    validatedFiles.isEmpty() ||
                    "base.apk" !in seenNames
                ) {
                    throw IllegalStateException(
                        "The backup must contain a verified base.apk file."
                    )
                }

                val splitIndexes = seenNames
                    .filter { it.startsWith("split_") }
                    .mapNotNull {
                        Regex("""^split_([0-9]+)\.apk$""")
                            .matchEntire(it)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    }
                    .sorted()

                for (index in splitIndexes.indices) {
                    if (splitIndexes[index] != index + 1) {
                        throw IllegalStateException(
                            "The APK backup is missing a split file."
                        )
                    }
                }

                val apkUris = ArrayList<Uri>()

                validatedFiles.forEach { apkFile ->

                    val uri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        apkFile
                    )

                    apkUris.add(uri)
                }

                mainHandler.post {

                    if (activityDestroyed) {
                        return@post
                    }

                    try {
                        val shareIntent = Intent(
                            Intent.ACTION_SEND_MULTIPLE
                        ).apply {

                            type =
                                "application/vnd.android.package-archive"

                            putParcelableArrayListExtra(
                                Intent.EXTRA_STREAM,
                                apkUris
                            )

                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )

                            clipData = ClipData.newUri(
                                contentResolver,
                                "Verified APK backup",
                                apkUris.first()
                            ).apply {

                                apkUris.drop(1).forEach { uri ->
                                    addItem(ClipData.Item(uri))
                                }
                            }
                        }

                        activity.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                "Share verified APK backup"
                            )
                        )

                        result.success(
                            mapOf(
                                "success" to true,
                                "fileCount" to apkUris.size,
                                "integrityVerified" to true
                            )
                        )

                    } catch (e: Exception) {
                        Log.e(
                            TAG,
                            "Unable to open APK sharing interface",
                            e
                        )

                        result.error(
                            "APK_SHARE_ERROR",
                            e.message
                                ?: "Unable to share APK backup.",
                            null
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "APK backup sharing verification failed",
                    e
                )

                mainHandler.post {
                    if (!activityDestroyed) {
                        result.error(
                            "APK_SHARE_VERIFICATION_ERROR",
                            e.message
                                ?: "APK backup verification failed.",
                            null
                        )
                    }
                }
            }

        }, "APK-Share-Worker").start()
    }
}
