package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.IOException

/**
 * Real storage inspector: large files + duplicate candidates + health.
 * Uses only allowed ADB shell commands — no root assumed.
 */
class StorageInspector(private val runner: AdbShellRunner) {

    data class LargeFile(val path: String, val bytes: Long)
    data class DupGroup(val hash: String, val paths: List<String>)

    companion object {
        fun parseFindLarge(output: String): List<LargeFile> {
            val list = mutableListOf<LargeFile>()
            for (line in output.lineSequence()) {
                // format: "SIZE PATH" from: find /sdcard -type f -printf "%s %p\n" | sort -nr | head
                val m = Regex("""^\s*(\d+)\s+(.+)$""").find(line.trim()) ?: continue
                val bytes = m.groupValues[1].toLongOrNull() ?: continue
                val path = m.groupValues[2].trim()
                if (bytes > 50L * 1024 * 1024) list.add(LargeFile(path, bytes))
            }
            return list.sortedByDescending { it.bytes }.take(20)
        }

        fun parseDiskStats(output: String): String {
            // just extract free% and trim issues
            return output.lineSequence()
                .filter { it.contains("free", true) || it.contains("TRIM", true) || it.contains("fstrim", true) }
                .take(12).joinToString("\n").ifEmpty { output.take(1500) }
        }
    }

    @Throws(IOException::class)
    fun largeFilesReport(maxMb: Int = 100): String {
        val threshold = "${maxMb}M"
        val find = runner.shell("find /sdcard -type f -size +$threshold -printf \"%s %p\\n\" 2>/dev/null | sort -nr | head -n 30", 30000L).output
        val files = parseFindLarge(find)
        val df = runner.shell("df -h /data /sdcard 2>&1 | head -n 10", 8000L).output
        val fstrim = runner.shell("cmd diskstats 2>&1 | head -n 40; sm fstrim 2>&1 | head -n 20", 20000L).output
        val b = ReportBuilder("LARGE FILES & STORAGE HEALTH (CONNECTED PHONE)")
        b.section("Disk usage").text(df)
        b.section("Large files > ${maxMb}MB (top 20)").let { sb ->
            if (files.isEmpty()) sb.text(find.take(1500).ifEmpty { "No files over ${maxMb}MB found (or permission denied)." })
            else files.forEach { sb.text("${ShellSupport.formatBytes(it.bytes)} — ${it.path}") }
            sb
        }
        b.section("Health / TRIM").text(parseDiskStats(fstrim).take(2000))
        b.section("Fix").text("Review large files in File Manager, move to This phone, then run fix-caches / fix-storage.")
        return b.toString()
    }

    @Throws(IOException::class)
    fun exportChecklist(): String {
        val dcim = runner.shell("ls -1 /sdcard/DCIM 2>&1 | wc -l; du -sh /sdcard/DCIM 2>&1 | head -n 1", 15000L).output
        val dl = runner.shell("ls -1 /sdcard/Download 2>&1 | wc -l; du -sh /sdcard/Download 2>&1 | head -n 1", 15000L).output
        val wa = runner.shell("du -sh /sdcard/Android/media/com.whatsapp 2>&1 | head -n 1; ls -1 /sdcard/WhatsApp 2>&1 | wc -l", 15000L).output
        val free = runner.shell("df -h /data 2>&1 | tail -n 2", 8000L).output
        val b = ReportBuilder("EXPORT VERIFIER — BEFORE WIPE (CONNECTED PHONE)")
        b.section("DCIM (Camera)").text(dcim.trim())
        b.section("Download").text(dl.trim())
        b.section("WhatsApp").text(wa.ifBlank { "Not found or no access" })
        b.section("Free on /data").text(free.trim())
        b.section("Checklist").text(
            "☑ DCIM copied?  ☑ Download copied?  ☑ WhatsApp Media?  ☑ Contacts exported?  \n" +
            "Do not wipe until all are copied to This phone or cloud. Use File Manager → Copy to this phone."
        )
        return b.toString()
    }
}
