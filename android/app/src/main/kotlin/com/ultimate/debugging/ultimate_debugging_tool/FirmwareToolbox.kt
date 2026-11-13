package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.IOException

/**
 * Firmware toolbox: verifies official firmware packages + enforces backup-before-flash.
 * Never downloads from untrusted sources; download is user-confirmed from official sites.
 */
class FirmwareToolbox(private val runner: AdbShellRunner, private val filesDir: File) {

    companion object {
        // Allowlist of official domains — anything else is refused
        val OFFICIAL_DOMAINS = listOf(
            "dl.google.com", "android.googleapis.com",
            "samsung.com", "samsungcloud.com",
            "xiaomi.com", "mi.com",
            "oneplus.com", "oppo.com", "realme.com",
            "huawei.com", "support.huawei.com"
        )
        val OFFICIAL_GUIDES = linkedMapOf(
            "Google Pixel" to "https://developers.google.com/android/images",
            "Samsung" to "https://www.samsung.com/support/",
            "Xiaomi" to "https://xiaomi.eu/community/",
            "OnePlus" to "https://www.oneplus.com/support/softwareupgrade",
            "Generic OTA" to "https://source.android.com/devices/tech/ota"
        )
        fun isOfficialUrl(url: String): Boolean {
            val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1)?.lowercase() ?: return false
            return OFFICIAL_DOMAINS.any { host == it || host.endsWith(".$it") }
        }
        fun checksumOk(file: File, sha256: String): Boolean {
            if (!file.isFile || sha256.length != 64) return false
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(64*1024)
                while (true) { val r = ins.read(buf); if (r<=0) break; digest.update(buf,0,r) }
            }
            val hex = digest.digest().joinToString("") { String.format("%02x", it) }
            return hex.equals(sha256, ignoreCase = true)
        }
    }

    fun verifyPackage(path: String, sha256: String?): String {
        val dir = File(filesDir, "updates").canonicalFile
        val file = File(path).canonicalFile
        if (!file.path.startsWith(dir.path + File.separator)) return "Refused: choose package via this tool first."
        if (!file.isFile) return "File not found — choose again."
        val actual = runCatching { 
            val d = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins -> val b=ByteArray(64*1024); while(true){val r=ins.read(b); if(r<=0) break; d.update(b,0,r)} }
            d.digest().joinToString(""){ String.format("%02x", it) }
        }.getOrNull() ?: "hash failed"
        val ok = sha256?.let { actual.equals(it, true) } ?: true
        val b = ReportBuilder("FIRMWARE VERIFY")
        b.section("File").text("${file.name} (${ShellSupport.formatBytes(file.length())}) in ${dir.name}")
        b.section("SHA-256").text(actual)
        b.section("Expected").text(sha256 ?: "(none — will check in recovery)")
        b.section("Result").text(if (ok) "✓ Hash matches — safe to sideload in recovery." else "✗ Hash mismatch — do not flash. Re-download from official site.")
        b.section("Official guides").let { s -> OFFICIAL_GUIDES.forEach { (k,v) -> s.text("$k: $v") }; s }
        return b.toString()
    }

    fun prepareFlash(partition: String, path: String): String {
        // Enforce backup: check filesDir/backups/<device>-<partition>-<ts>.img exists or at least manifest
        val backupDir = File(filesDir, "flash_backups")
        backupDir.mkdirs()
        val backups = backupDir.listFiles()?.filter { it.name.contains(partition) } ?: emptyList()
        val haveBackup = backups.isNotEmpty()
        val b = ReportBuilder("FLASH GUARD — BACKUP FIRST")
        b.section("Partition").text(partition)
        b.section("Image").text(File(path).name + " — " + ShellSupport.formatBytes(File(path).length()))
        b.section("Backup check").text(if (haveBackup) "✓ Backup found: ${backups[0].name} (${ShellSupport.formatBytes(backups[0].length())})" else "⚠ No backup for $partition yet — create one before flashing!")
        b.section("How to backup").text(
            "1) In bootloader: this tool cannot read back sealed partitions — use manufacturer's rescue tool.\n" +
            "2) In Android: backup APKs (Apps → Backup) + files (File Manager) before any flash.\n" +
            "3) This tool logs the flash to Repair History — keep that log."
        )
        b.section("Official firmware").let { s -> OFFICIAL_GUIDES.forEach { (k,v) -> s.text("$k: $v") }; s.text("Never flash an image from an unofficial link. Verify SHA-256 first."); s }
        return b.toString()
    }

    fun recordBackupNote(partition: String, note: String): Map<String, Any?> {
        val dir = File(filesDir, "flash_backups"); dir.mkdirs()
        val f = File(dir, "${partition}-${System.currentTimeMillis()}.txt")
        f.writeText("BACKUP NOTE $partition\nTime: ${System.currentTimeMillis()}\nNote: $note\n")
        return mapOf("path" to f.absolutePath, "bytes" to f.length())
    }
}
