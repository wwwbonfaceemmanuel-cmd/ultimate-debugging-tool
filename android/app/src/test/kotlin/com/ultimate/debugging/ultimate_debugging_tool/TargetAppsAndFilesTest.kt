package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TargetAppsAndFilesTest {

    companion object {
        private val key: SoftwareAdbKey by lazy {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            SoftwareAdbKey(pair.public as RSAPublicKey, pair.private as RSAPrivateKey)
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02X".format(it) }
    }

    private val baseApk = ByteArray(150000) { (it % 251).toByte() }
    private val splitApk = ByteArray(70000) { (it % 13).toByte() }

    /** When true the fake phone reports a wrong checksum. */
    @Volatile private var corruptChecksum = false

    private val device: FakeAdbDevice = FakeAdbDevice { command ->
        when {
            command == "pm path 'com.example.app'" -> FakeAdbDevice.Reply(
                "package:/data/app/~~x/com.example.app/split_config.en.apk\n" +
                    "package:/data/app/~~x/com.example.app/base.apk\n"
            )
            command.startsWith("pm path") -> FakeAdbDevice.Reply("")
            command.startsWith("sha256sum ") -> {
                val path = command.removePrefix("sha256sum ").trim('\'')
                val bytes = if (path.endsWith("base.apk")) baseApk else splitApk
                val hash = if (corruptChecksum) "0".repeat(64) else sha256(bytes).lowercase()
                FakeAdbDevice.Reply("$hash  $path\n")
            }
            command == "dumpsys package 'com.example.app'" -> FakeAdbDevice.Reply(DUMP)
            command == "pidof 'com.example.app'" -> FakeAdbDevice.Reply("4321\n")
            else -> FakeAdbDevice.Reply("")
        }
    }.apply {
        files["/data/app/~~x/com.example.app/base.apk"] = baseApk
        files["/data/app/~~x/com.example.app/split_config.en.apk"] = splitApk
        files["/sdcard/DCIM/photo.jpg"] = ByteArray(90000) { (it % 97).toByte() }
        files["/sdcard/notes.txt"] = "hello".toByteArray()
        files["/sdcard/Download/b.txt"] = ByteArray(3)
        files["/sdcard/Download/A.txt"] = ByteArray(4)
    }

    private fun session(): AdbSessionManager {
        device.trustedKeys.add(key.publicKey.modulus)
        val session = AdbSessionManager(AdbAuthentication { key })
        val results = LinkedBlockingQueue<AdbSessionManager.ConnectResult>()
        session.connect(AdbSessionManager.Link.USB, "test", transportFactory = { device.transport }) {
            results.put(it)
        }
        assertTrue(results.poll(10, TimeUnit.SECONDS) is AdbSessionManager.ConnectResult.Connected)
        return session
    }

    private fun tempDir(): File = Files.createTempDirectory("udt-test").toFile()

    // ---------- Apps: parsers ----------

    @Test
    fun packageListIsParsed() {
        assertEquals(
            listOf("com.a", "com.b", "com.c"),
            TargetApps.parsePackageList(
                "package:com.b\npackage:/data/app/x/base.apk=com.c\nnoise\npackage:com.a\npackage:com.a\n"
            )
        )
        assertTrue(TargetApps.parsePackageList("error: no devices").isEmpty())
    }

    @Test
    fun packageDumpIsParsed() {
        val details = TargetApps.parsePackageDump(DUMP)

        assertEquals("2.24.1", details["versionName"])
        assertEquals("2241", details["versionCode"])
        assertEquals("34", details["targetSdk"])
        assertEquals("23", details["minSdk"])
        assertEquals("com.android.vending", details["installer"])
        assertEquals(false, details["system"])
        assertEquals(true, details["enabled"])
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.READ_CONTACTS"),
            details["requestedPermissions"]
        )
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.CAMERA"),
            details["grantedPermissions"]
        )
    }

    // ---------- Apps: against the fake phone ----------

    @Test
    fun detailsCombineDumpPathsAndRunningState() {
        val session = session()
        val details = TargetApps(session).details("com.example.app")

        assertEquals("com.example.app", details["packageName"])
        assertEquals(1, details["splitCount"])
        assertEquals(true, details["running"])
        assertEquals("2.24.1", details["versionName"])
        session.shutdown()
    }

    @Test
    fun detailsRefuseBadOrMissingPackages() {
        val session = session()

        for (name in listOf("com.example.app; reboot", "\$(id)", "", "nodots")) {
            try {
                TargetApps(session).details(name)
                fail("'$name' should be refused")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("valid"))
            }
        }

        try {
            TargetApps(session).details("com.not.installed")
            fail("expected not found")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("not found"))
        }

        session.shutdown()
    }

    @Test
    fun backupCopiesBaseAndSplitsAndVerifiesThem() {
        val session = session()
        val root = tempDir()

        val backup = TargetApps(session).backupApk("com.example.app", root)

        assertEquals(2, backup["fileCount"])
        assertEquals(true, backup["isSplitApk"])
        assertEquals((baseApk.size + splitApk.size).toLong(), backup["totalSizeBytes"])

        val directory = File(backup["backupDirectory"] as String)
        assertTrue(directory.canonicalPath.startsWith(root.canonicalPath))
        assertArrayEquals(baseApk, File(directory, "base.apk").readBytes())
        assertArrayEquals(splitApk, File(directory, "split_1.apk").readBytes())

        @Suppress("UNCHECKED_CAST")
        val files = backup["files"] as List<Map<String, Any>>
        assertEquals("base.apk", files[0]["fileName"])
        assertEquals(sha256(baseApk), files[0]["sha256"])
        assertEquals("SHA-256 matches the phone", files[0]["verification"])
        assertEquals(sha256(splitApk), files[1]["sha256"])

        session.shutdown()
    }

    @Test
    fun backupIsRejectedAndRemovedWhenChecksumDiffers() {
        corruptChecksum = true
        val session = session()
        val root = tempDir()

        try {
            TargetApps(session).backupApk("com.example.app", root)
            fail("expected a verification failure")
        } catch (expected: IOException) {
            assertTrue(expected.message!!, expected.message!!.contains("verification failed"))
        }

        // No half-finished or unverified copy is left behind.
        assertTrue(root.walkTopDown().none { it.isFile })
        session.shutdown()
    }

    // ---------- Files ----------

    @Test
    fun pathsCannotClimbAboveTheRoot() {
        assertEquals("/sdcard/DCIM", TargetFiles.normalize("/sdcard//DCIM/"))
        assertEquals("/sdcard", TargetFiles.normalize("/sdcard/DCIM/.."))
        assertEquals("/", TargetFiles.normalize("/../../.."))
        assertEquals("/etc", TargetFiles.normalize("/sdcard/../../etc"))
        assertEquals("/sdcard/a b", TargetFiles.normalize("  /sdcard/./a b "))

        for (bad in listOf("sdcard", "", "../etc", "/sdcard/\u0000x")) {
            try {
                TargetFiles.normalize(bad)
                fail("'$bad' should be refused")
            } catch (expected: IOException) {
                // refused
            }
        }

        assertNull(TargetFiles.parentOf("/"))
        assertEquals("/", TargetFiles.parentOf("/sdcard"))
        assertEquals("/sdcard", TargetFiles.parentOf("/sdcard/DCIM"))
    }

    @Test
    fun localNamesAreMadeSafe() {
        assertEquals("photo.jpg", TargetFiles.safeLocalName("photo.jpg"))
        assertEquals("_.._passwd", TargetFiles.safeLocalName("/../passwd").trimStart('.'))
        assertFalse(TargetFiles.safeLocalName("a/b\\c").contains('/'))
        assertFalse(TargetFiles.safeLocalName("a/b\\c").contains('\\'))
        assertEquals("file", TargetFiles.safeLocalName("..."))
        assertEquals(120, TargetFiles.safeLocalName("x".repeat(500)).length)
    }

    @Test
    fun listPutsFoldersFirstAndBuildsFullPaths() {
        val session = session()
        val listing = TargetFiles(session).list("/sdcard/")

        assertEquals("/sdcard", listing["path"])
        assertEquals("/", listing["parent"])

        @Suppress("UNCHECKED_CAST")
        val entries = listing["entries"] as List<Map<String, Any?>>
        assertEquals(listOf("DCIM", "Download", "notes.txt"), entries.map { it["name"] })
        assertEquals("/sdcard/DCIM", entries[0]["path"])
        assertEquals("directory", entries[0]["type"])
        assertEquals("file", entries[2]["type"])
        assertEquals(5L, entries[2]["sizeBytes"])

        @Suppress("UNCHECKED_CAST")
        val downloads = TargetFiles(session).list("/sdcard/Download")["entries"] as List<Map<String, Any?>>
        assertEquals(listOf("A.txt", "b.txt"), downloads.map { it["name"] })

        session.shutdown()
    }

    @Test
    fun listExplainsMissingFolderAndFile() {
        val session = session()

        try {
            TargetFiles(session).list("/sdcard/nothing-here")
            fail("expected an error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("does not exist"))
        }

        try {
            TargetFiles(session).list("/sdcard/notes.txt")
            fail("expected an error")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("file, not a folder"))
        }

        session.shutdown()
    }

    @Test
    fun pullCopiesTheFileAndReportsItsChecksum() {
        val session = session()
        val folder = tempDir()

        val result = TargetFiles(session).pull("/sdcard/DCIM/../DCIM/photo.jpg", folder)
        val local = File(result["localPath"] as String)

        assertEquals("/sdcard/DCIM/photo.jpg", result["remotePath"])
        assertEquals("photo.jpg", result["fileName"])
        assertEquals(90000L, result["sizeBytes"])
        assertArrayEquals(device.files["/sdcard/DCIM/photo.jpg"]!!, local.readBytes())
        assertEquals(sha256(local.readBytes()), result["sha256"])
        assertTrue(local.canonicalPath.startsWith(folder.canonicalPath))

        session.shutdown()
    }

    @Test
    fun pullRefusesFoldersAndMissingFilesAndLeavesNothingBehind() {
        val session = session()
        val folder = tempDir()

        for (path in listOf("/sdcard/DCIM", "/sdcard/missing.bin")) {
            try {
                TargetFiles(session).pull(path, folder)
                fail("'$path' should fail")
            } catch (expected: IOException) {
                // expected
            }
        }

        assertTrue(folder.listFiles()!!.isEmpty())
        session.shutdown()
    }

    @Test
    fun pushStoresTheFileInTheChosenFolder() {
        val session = session()
        val content = ByteArray(130000) { (it % 31).toByte() }

        val result = TargetFiles(session).push(
            ByteArrayInputStream(content), "report.pdf", "/sdcard/Download/"
        )

        assertEquals("/sdcard/Download/report.pdf", result["remotePath"])
        assertEquals(130000L, result["sizeBytes"])
        assertArrayEquals(content, device.files["/sdcard/Download/report.pdf"]!!)

        session.shutdown()
    }

    @Test
    fun pushCannotEscapeTheFolderThroughTheFileName() {
        val session = session()

        // Only the last part of a name is ever used.
        val result = TargetFiles(session).push(
            ByteArrayInputStream(ByteArray(10)), "../../system/evil.sh", "/sdcard/Download"
        )
        assertEquals("/sdcard/Download/evil.sh", result["remotePath"])

        for (bad in listOf("", "..", ".", "dir/")) {
            try {
                TargetFiles(session).push(ByteArrayInputStream(ByteArray(1)), bad, "/sdcard")
                fail("'$bad' should be refused")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("not valid"))
            }
        }

        session.shutdown()
    }
}

private const val DUMP = """Packages:
  Package [com.example.app] (a1b2c3):
    userId=10234
    pkg=Package{1234 com.example.app}
    codePath=/data/app/~~x/com.example.app
    versionCode=2241 minSdk=23 targetSdk=34
    versionName=2.24.1
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    dataDir=/data/user/0/com.example.app
    firstInstallTime=2024-01-05 10:11:12
    lastUpdateTime=2024-06-01 08:00:00
    installerPackageName=com.android.vending
    requested permissions:
      android.permission.INTERNET
      android.permission.CAMERA
      android.permission.READ_CONTACTS
    install permissions:
      android.permission.INTERNET: granted=true
    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false notLaunched=false enabled=0 instant=false virtual=false
      runtime permissions:
        android.permission.CAMERA: granted=true, flags=[ USER_SET ]
        android.permission.READ_CONTACTS: granted=false, flags=[ USER_SET ]
"""
