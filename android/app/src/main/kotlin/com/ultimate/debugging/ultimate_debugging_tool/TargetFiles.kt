package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** File manager operations on the CONNECTED phone, over ADB. */
class TargetFiles(private val runner: AdbShellRunner) {

    companion object {
        const val MAX_PULL_BYTES = 2L * 1024 * 1024 * 1024

        /** Starting points that exist on almost every phone. */
        val SHORTCUTS: List<Pair<String, String>> = listOf(
            "Internal storage" to "/sdcard",
            "Downloads" to "/sdcard/Download",
            "Camera" to "/sdcard/DCIM",
            "Pictures" to "/sdcard/Pictures",
            "Documents" to "/sdcard/Documents",
            "WhatsApp media" to "/sdcard/Android/media/com.whatsapp/WhatsApp/Media",
            "Root folder" to "/"
        )

        /**
         * Returns a clean absolute path with "." and ".." resolved, so a
         * path can never climb above the root.
         */
        @Throws(IOException::class)
        fun normalize(path: String): String {
            val trimmed = path.trim()

            if (!trimmed.startsWith("/") || trimmed.contains('\u0000')) {
                throw IOException("Enter a full path that starts with /")
            }

            val parts = ArrayList<String>()

            for (part in trimmed.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(part)
                }
            }

            return "/" + parts.joinToString("/")
        }

        fun parentOf(path: String): String? {
            if (path == "/") return null
            return path.substringBeforeLast('/').ifEmpty { "/" }
        }

        /** A file name that is safe to create on the workstation. */
        fun safeLocalName(name: String): String {
            val cleaned = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.')
            return cleaned.ifEmpty { "file" }.take(120)
        }
    }

    /** Lists a folder: sub-folders first, then files, by name. */
    @Throws(IOException::class)
    fun list(path: String): Map<String, Any?> {
        val folder = normalize(path)

        val entries = AdbFileSync.open(runner).use { sync ->
            // A trailing slash makes the phone follow links such as /sdcard.
            val listed = sync.list(if (folder == "/") "/" else "$folder/")

            if (listed.isEmpty()) {
                val info = sync.stat(folder)

                when {
                    info == null -> throw IOException(
                        "This folder does not exist or the phone does not allow access to it."
                    )
                    info.isFile -> throw IOException("This is a file, not a folder.")
                    else -> Unit
                }
            }

            listed
        }

        val sorted = entries.sortedWith(
            compareBy<AdbFileSync.Entry> { !(it.isDirectory || it.isLink) }
                .thenBy { it.name.lowercase() }
        )

        return mapOf(
            "path" to folder,
            "parent" to parentOf(folder),
            "entries" to sorted.map { entry ->
                entry.toMap() + mapOf(
                    "path" to if (folder == "/") "/${entry.name}" else "$folder/${entry.name}"
                )
            }
        )
    }

    /** Copies one file from the phone into [destinationDirectory]. */
    @Throws(IOException::class)
    fun pull(
        remotePath: String,
        destinationDirectory: File
    ): Map<String, Any> {
        val source = normalize(remotePath)

        if (!destinationDirectory.mkdirs() && !destinationDirectory.isDirectory) {
            throw IOException("Unable to create the folder for copied files.")
        }

        val name = safeLocalName(source.substringAfterLast('/'))
        val destination = File(
            destinationDirectory,
            "${System.currentTimeMillis()}-$name"
        )

        try {
            AdbFileSync.open(runner).use { sync ->
                val info = sync.stat(source)
                    ?: throw IOException("The file does not exist or cannot be read.")

                if (info.isDirectory) {
                    throw IOException("Folders cannot be copied. Open the folder and copy files.")
                }

                if (info.size > MAX_PULL_BYTES) {
                    throw IOException("The file is larger than 2 GB and cannot be copied.")
                }

                if (info.size > destinationDirectory.usableSpace - 50L * 1024 * 1024) {
                    throw IOException("There is not enough free space on this phone for the copy.")
                }

                val copied = FileOutputStream(destination).use { output ->
                    val count = sync.pull(source, output, MAX_PULL_BYTES)
                    output.fd.sync()
                    count
                }

                if (info.isFile && copied != info.size) {
                    throw IOException("The copy is incomplete. Try again.")
                }
            }

            return mapOf(
                "remotePath" to source,
                "localPath" to destination.absolutePath,
                "fileName" to name,
                "sizeBytes" to destination.length(),
                "sha256" to TargetApps.sha256(destination)
            )
        } catch (error: Exception) {
            destination.delete()

            if (error is IOException) throw error

            throw IOException(error.message ?: "The file copy failed.", error)
        }
    }

    /** Sends a file to a folder on the phone and checks it arrived. */
    @Throws(IOException::class)
    fun push(
        input: InputStream,
        fileName: String,
        remoteDirectory: String
    ): Map<String, Any> {
        val folder = normalize(remoteDirectory)
        val name = fileName.substringAfterLast('/').trim()

        if (name.isEmpty() || name == "." || name == ".." || name.contains('\u0000')) {
            throw IOException("The file name is not valid.")
        }

        val target = if (folder == "/") "/$name" else "$folder/$name"

        return AdbFileSync.open(runner).use { sync ->
            val sent = sync.push(input, target)
            val arrived = sync.stat(target)?.size

            if (arrived != sent) {
                throw IOException("The phone did not store the complete file.")
            }

            mapOf(
                "remotePath" to target,
                "fileName" to name,
                "sizeBytes" to sent
            )
        }
    }
}
