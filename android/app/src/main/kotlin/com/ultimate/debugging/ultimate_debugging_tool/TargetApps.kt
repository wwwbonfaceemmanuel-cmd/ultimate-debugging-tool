package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Inspects and backs up apps installed on the CONNECTED phone.
 */
class TargetApps(private val runner: AdbShellRunner) {

    companion object {
        private const val MAX_APK_BYTES = 4L * 1024 * 1024 * 1024

        /** Parses `pm list packages -f` style lines into package names. */
        fun parsePackageList(output: String): List<String> {
            return output.lineSequence()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim().substringAfterLast('=') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
                .toList()
        }

        /** Extracts the main facts from `dumpsys package <name>`. */
        fun parsePackageDump(dump: String): Map<String, Any> {
            fun first(pattern: String): String? =
                Regex(pattern).find(dump)?.groupValues?.get(1)?.trim()

            val requested = LinkedHashSet<String>()
            val granted = LinkedHashSet<String>()
            var section = ""

            for (raw in dump.lineSequence()) {
                val line = raw.trim()

                when {
                    line == "requested permissions:" -> section = "requested"
                    line == "install permissions:" -> section = "install"
                    line == "runtime permissions:" -> section = "runtime"
                    line.endsWith(":") && !line.contains("permission") -> section = ""
                    section == "requested" && line.contains('.') ->
                        requested.add(line.substringBefore(':'))
                    (section == "install" || section == "runtime") &&
                        line.contains("granted=true") ->
                        granted.add(line.substringBefore(':'))
                }
            }

            val flags = first("\\bflags=\\[ ?([^\\]]*)]").orEmpty()

            return mapOf(
                "versionName" to (first("versionName=(\\S+)") ?: ""),
                "versionCode" to (first("versionCode=(\\d+)") ?: ""),
                "targetSdk" to (first("targetSdk=(\\d+)") ?: ""),
                "minSdk" to (first("minSdk=(\\d+)") ?: ""),
                "installer" to (first("installerPackageName=(\\S+)")?.takeIf { it != "null" } ?: ""),
                "firstInstalled" to (first("firstInstallTime=(.+)") ?: ""),
                "lastUpdated" to (first("lastUpdateTime=(.+)") ?: ""),
                "dataDir" to (first("dataDir=(\\S+)") ?: ""),
                "system" to flags.split(' ').contains("SYSTEM"),
                "debuggable" to flags.split(' ').contains("DEBUGGABLE"),
                "enabled" to (first("\\benabled=(\\d)")?.let { it == "0" || it == "1" } ?: true),
                "requestedPermissions" to requested.toList(),
                "grantedPermissions" to granted.toList()
            )
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")

            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }

            return digest.digest().joinToString("") {
                "%02X".format(it.toInt() and 0xFF)
            }
        }
    }

    /** Lists installed packages with their type and state. */
    @Throws(IOException::class)
    fun list(): List<Map<String, Any>> {
        val data = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "all" to "pm list packages",
                "system" to "pm list packages -s",
                "disabled" to "pm list packages -d"
            ),
            timeoutMs = 60000L
        )

        val all = parsePackageList(data["all"].orEmpty())

        if (all.isEmpty()) {
            throw IOException("The phone did not return its list of apps.")
        }

        val system = parsePackageList(data["system"].orEmpty()).toSet()
        val disabled = parsePackageList(data["disabled"].orEmpty()).toSet()

        return all.map {
            mapOf(
                "packageName" to it,
                "isSystemApp" to (it in system),
                "enabled" to (it !in disabled)
            )
        }
    }

    private fun requirePackage(packageName: String): String {
        if (!ShellSupport.isPackageName(packageName)) {
            throw IOException("Enter a valid application package name.")
        }

        return ShellSupport.quote(packageName)
    }

    private fun apkPaths(quoted: String): List<String> {
        return runner.shell("pm path $quoted").output.lineSequence()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.startsWith("/") && it.endsWith(".apk") }
            .toList()
    }

    /** Details and permissions of one app on the connected phone. */
    @Throws(IOException::class)
    fun details(packageName: String): Map<String, Any> {
        val quoted = requirePackage(packageName)
        val paths = apkPaths(quoted)

        if (paths.isEmpty()) {
            throw IOException("The application was not found on the connected device.")
        }

        val dump = runner.shell("dumpsys package $quoted", 45000L).output
        val running = runner.shell("pidof $quoted").output.trim()

        return LinkedHashMap<String, Any>(parsePackageDump(dump)).apply {
            put("packageName", packageName)
            put("apkPaths", paths)
            put("splitCount", (paths.size - 1).coerceAtLeast(0))
            put("running", running.isNotEmpty() && running.all { it.isDigit() || it == ' ' })
        }
    }

    /**
     * Copies an app's APK files from the connected phone into
     * [exportRoot] and verifies every copy.
     */
    @Throws(IOException::class)
    fun backupApk(
        packageName: String,
        exportRoot: File,
        onProgress: ((String) -> Unit)? = null
    ): Map<String, Any> {
        val quoted = requirePackage(packageName)
        val paths = apkPaths(quoted)

        if (paths.isEmpty()) {
            throw IOException("The application was not found on the connected device.")
        }

        // base.apk first, then the splits in a stable order.
        val ordered = paths.sortedWith(
            compareBy<String> { !it.endsWith("/base.apk") }.thenBy { it }
        )

        val safeName = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val directory = File(exportRoot, "$safeName-${System.currentTimeMillis()}")

        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create the APK backup directory.")
        }

        try {
            val files = ArrayList<Map<String, Any>>()
            var totalBytes = 0L

            AdbFileSync.open(runner).use { sync ->
                ordered.forEachIndexed { index, remotePath ->
                    val fileName = if (index == 0) "base.apk" else "split_$index.apk"
                    val destination = File(directory, fileName)

                    onProgress?.invoke(fileName)

                    val expected = sync.stat(remotePath)?.size
                        ?: throw IOException("An APK file could not be read from the phone.")

                    val copied = FileOutputStream(destination).use { output ->
                        val count = sync.pull(remotePath, output, MAX_APK_BYTES)
                        output.fd.sync()
                        count
                    }

                    if (copied != expected || destination.length() != expected) {
                        throw IOException("File size verification failed for $fileName")
                    }

                    val localHash = sha256(destination)

                    // Compare with the phone's own checksum when it has the tool.
                    val remoteHash = runner.shell(
                        "sha256sum ${ShellSupport.quote(remotePath)}",
                        120000L
                    ).output.trim().substringBefore(' ').uppercase()

                    val hashChecked = remoteHash.length == 64

                    if (hashChecked && remoteHash != localHash) {
                        throw IOException("SHA-256 verification failed for $fileName")
                    }

                    totalBytes += copied

                    files.add(
                        mapOf(
                            "fileName" to fileName,
                            "sizeBytes" to copied,
                            "sha256" to localHash,
                            "verified" to true,
                            "verification" to if (hashChecked) {
                                "SHA-256 matches the phone"
                            } else {
                                "Size matches the phone"
                            }
                        )
                    )
                }
            }

            return mapOf(
                "success" to true,
                "packageName" to packageName,
                "source" to "target",
                "backupDirectory" to directory.absolutePath,
                "fileCount" to files.size,
                "totalSizeBytes" to totalBytes,
                "isSplitApk" to (files.size > 1),
                "integrityVerified" to true,
                "files" to files
            )
        } catch (error: Exception) {
            directory.deleteRecursively()

            if (error is IOException) throw error

            throw IOException(error.message ?: "The APK backup failed.", error)
        }
    }
}
