
package com.ultimate.debugging.ultimate_debugging_tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object RepairHistoryStore {

    private const val FILE_NAME = "repair_history.json"
    private const val MAX_RECORDS = 500

    private fun historyFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    @Synchronized
    fun save(
        context: Context,
        operation: String,
        category: String,
        result: String,
        details: String = ""
    ): Map<String, Any> {

        require(operation.isNotBlank()) {
            "Operation name is required."
        }

        val records = readArray(context)

        val entry = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("timestamp", System.currentTimeMillis())
            put("operation", operation)
            put("category", category)
            put("result", result)
            put("details", details)
        }

        records.put(entry)

        val trimmedRecords = JSONArray()

        val startIndex = maxOf(0, records.length() - MAX_RECORDS)

        for (index in startIndex until records.length()) {
            trimmedRecords.put(records.getJSONObject(index))
        }

        val file = historyFile(context)
        val temporaryFile = File(context.filesDir, "$FILE_NAME.tmp")

        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(trimmedRecords.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }

            if (file.exists() && !file.delete()) {
                throw IllegalStateException(
                    "Unable to replace repair history file."
                )
            }

            if (!temporaryFile.renameTo(file)) {
                throw IllegalStateException(
                    "Unable to save repair history."
                )
            }

        } catch (e: Exception) {
            temporaryFile.delete()
            throw e
        }

        return entryToMap(entry)
    }

    @Synchronized
    fun getAll(context: Context): List<Map<String, Any>> {

        val records = readArray(context)
        val history = mutableListOf<Map<String, Any>>()

        for (index in records.length() - 1 downTo 0) {
            history.add(
                entryToMap(records.getJSONObject(index))
            )
        }

        return history
    }

    @Synchronized
    fun clear(context: Context): Boolean {

        val file = historyFile(context)

        if (!file.exists()) {
            return true
        }

        return file.delete()
    }

    private fun readArray(context: Context): JSONArray {

        val file = historyFile(context)

        if (!file.exists()) {
            return JSONArray()
        }

        val content = file.readText(Charsets.UTF_8)

        if (content.isBlank()) {
            return JSONArray()
        }

        return JSONArray(content)
    }

    private fun entryToMap(
        entry: JSONObject
    ): Map<String, Any> {

        return mapOf(
            "id" to entry.optString("id"),
            "timestamp" to entry.optLong("timestamp"),
            "operation" to entry.optString("operation"),
            "category" to entry.optString("category"),
            "result" to entry.optString("result"),
            "details" to entry.optString("details")
        )
    }
}
