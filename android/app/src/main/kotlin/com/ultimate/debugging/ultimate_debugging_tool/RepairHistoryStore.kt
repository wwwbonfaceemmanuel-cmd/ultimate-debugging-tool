package com.ultimate.debugging.ultimate_debugging_tool

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Service history kept on the technician's phone.
 *
 * Each record says what was done, to which phone, and how it ended.
 * The file is replaced in one step so a crash cannot leave it half
 * written, and a damaged file is set aside instead of blocking the app.
 */
object RepairHistoryStore {

    private const val FILE_NAME = "repair_history.json"
    private const val MAX_RECORDS = 500
    private const val MAX_DETAILS_LENGTH = 20000

    private val TEXT_FIELDS = listOf(
        "id", "operation", "category", "result", "details",
        "scope", "deviceLabel", "deviceSerial", "link"
    )

    private fun historyFile(directory: File): File = File(directory, FILE_NAME)

    /**
     * @param scope "target" for work on a connected phone, "workstation"
     *        for reports about the technician's own phone.
     */
    @Synchronized
    fun save(
        directory: File,
        operation: String,
        category: String,
        result: String,
        details: String = "",
        scope: String = "workstation",
        deviceLabel: String = "",
        deviceSerial: String = "",
        link: String = ""
    ): Map<String, Any> {
        require(operation.isNotBlank()) { "Operation name is required." }

        val records = readArray(directory)

        val trimmedDetails = if (details.length > MAX_DETAILS_LENGTH) {
            details.take(MAX_DETAILS_LENGTH) + "\n… (shortened)"
        } else {
            details
        }

        val entry = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("operation", operation.trim())
            put("category", category.ifBlank { "General" })
            put("result", result.ifBlank { "Unknown" })
            put("details", trimmedDetails)
            put("scope", scope)
            put("deviceLabel", deviceLabel)
            put("deviceSerial", deviceSerial)
            put("link", link)
        }

        records.put(entry)

        val kept = JSONArray()
        val start = maxOf(0, records.length() - MAX_RECORDS)

        for (index in start until records.length()) {
            kept.put(records.getJSONObject(index))
        }

        writeArray(directory, kept)

        return entryToMap(entry)
    }

    /** Newest first. */
    @Synchronized
    fun getAll(directory: File): List<Map<String, Any>> {
        val records = readArray(directory)
        val history = ArrayList<Map<String, Any>>()

        for (index in records.length() - 1 downTo 0) {
            val entry = records.optJSONObject(index) ?: continue
            history.add(entryToMap(entry))
        }

        return history
    }

    @Synchronized
    fun clear(directory: File): Boolean {
        val file = historyFile(directory)
        return !file.exists() || file.delete()
    }

    /** Readable report of the whole history, newest first. */
    @Synchronized
    fun exportText(directory: File): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val records = getAll(directory)

        return buildString {
            appendLine("ULTIMATE DEBUGGING TOOL - SERVICE HISTORY")
            appendLine("Exported: ${format.format(Date())}")
            appendLine("Records: ${records.size}")

            for (record in records) {
                appendLine()
                appendLine("-".repeat(40))
                appendLine("Date: ${format.format(Date(record["timestamp"] as Long))}")
                appendLine("Operation: ${record["operation"]}")
                appendLine("Category: ${record["category"]}")
                appendLine("Result: ${record["result"]}")

                val label = record["deviceLabel"].toString()
                val serial = record["deviceSerial"].toString()

                appendLine(
                    "Phone: " + when {
                        record["scope"] != "target" -> "This phone (workstation)"
                        label.isBlank() -> "Connected phone"
                        serial.isBlank() -> label
                        else -> "$label, serial $serial"
                    }
                )

                val details = record["details"].toString()

                if (details.isNotBlank()) {
                    appendLine()
                    appendLine(details.trim())
                }
            }
        }
    }

    private fun writeArray(directory: File, records: JSONArray) {
        directory.mkdirs()

        val file = historyFile(directory)
        val temporary = File(directory, "$FILE_NAME.tmp")

        try {
            FileOutputStream(temporary).use { output ->
                output.write(records.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            // Renaming over the old file replaces it in a single step.
            if (!temporary.renameTo(file)) {
                throw IllegalStateException("Unable to save repair history.")
            }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    private fun readArray(directory: File): JSONArray {
        val file = historyFile(directory)

        if (!file.exists()) return JSONArray()

        return try {
            val content = file.readText(Charsets.UTF_8)

            if (content.isBlank()) JSONArray() else JSONArray(content)
        } catch (_: Exception) {
            // Keep the damaged file for inspection and start a new one.
            file.renameTo(
                File(directory, "repair_history.damaged-${System.currentTimeMillis()}.json")
            )
            JSONArray()
        }
    }

    private fun entryToMap(entry: JSONObject): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()

        for (field in TEXT_FIELDS) {
            map[field] = entry.optString(field, "")
        }

        if ((map["scope"] as String).isEmpty()) map["scope"] = "workstation"

        map["timestamp"] = entry.optLong("timestamp")
        return map
    }
}
