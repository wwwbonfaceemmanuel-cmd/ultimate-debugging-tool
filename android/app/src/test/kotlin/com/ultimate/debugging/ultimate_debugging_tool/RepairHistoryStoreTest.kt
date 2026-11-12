package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepairHistoryStoreTest {

    private fun directory(): File = Files.createTempDirectory("udt-history").toFile()

    @Test
    fun savesAndReturnsNewestFirst() {
        val dir = directory()
        RepairHistoryStore.save(dir, "First", "Diagnostics", "Completed")
        RepairHistoryStore.save(
            dir, "Second", "Repair - Apps", "Failed", "details here",
            scope = "target", deviceLabel = "Samsung A12 (Android 12)",
            deviceSerial = "R58N123", link = "USB"
        )

        val all = RepairHistoryStore.getAll(dir)

        assertEquals(2, all.size)
        assertEquals("Second", all[0]["operation"])
        assertEquals("target", all[0]["scope"])
        assertEquals("R58N123", all[0]["deviceSerial"])
        assertEquals("workstation", all[1]["scope"])
        assertFalse(all[0]["id"] == all[1]["id"])
        assertTrue((all[0]["timestamp"] as Long) > 0)
    }

    @Test
    fun keepsOnlyTheLatestFiveHundred() {
        val dir = directory()
        repeat(505) { RepairHistoryStore.save(dir, "op $it", "c", "Completed") }

        val all = RepairHistoryStore.getAll(dir)

        assertEquals(500, all.size)
        assertEquals("op 504", all.first()["operation"])
        assertEquals("op 5", all.last()["operation"])
    }

    @Test
    fun damagedFileIsSetAsideInsteadOfBlocking() {
        val dir = directory()
        File(dir, "repair_history.json").writeText("{ this is not valid json")

        assertTrue(RepairHistoryStore.getAll(dir).isEmpty())
        RepairHistoryStore.save(dir, "After damage", "c", "Completed")

        assertEquals(1, RepairHistoryStore.getAll(dir).size)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("repair_history.damaged-") })
    }

    @Test
    fun oldRecordsWithoutNewFieldsStillLoad() {
        val dir = directory()
        File(dir, "repair_history.json").writeText(
            """[{"id":"1","timestamp":1700000000000,"operation":"Old","category":"c","result":"Completed","details":"d"}]"""
        )

        val record = RepairHistoryStore.getAll(dir).single()

        assertEquals("Old", record["operation"])
        assertEquals("workstation", record["scope"])
        assertEquals("", record["deviceLabel"])
    }

    @Test
    fun longDetailsAreShortened() {
        val dir = directory()
        RepairHistoryStore.save(dir, "Big", "c", "Completed", "x".repeat(50000))

        val details = RepairHistoryStore.getAll(dir).single()["details"] as String
        assertTrue(details.length < 21000)
        assertTrue(details.endsWith("(shortened)"))
    }

    @Test
    fun exportNamesThePhoneAndClearEmpties() {
        val dir = directory()
        RepairHistoryStore.save(
            dir, "Restart Wi-Fi", "Repair - Network", "Completed", "ok",
            scope = "target", deviceLabel = "Tecno Spark 10", deviceSerial = "ABC123"
        )

        val text = RepairHistoryStore.exportText(dir)
        assertTrue(text.contains("Restart Wi-Fi"))
        assertTrue(text.contains("Tecno Spark 10, serial ABC123"))
        assertTrue(text.contains("Records: 1"))

        assertTrue(RepairHistoryStore.clear(dir))
        assertTrue(RepairHistoryStore.getAll(dir).isEmpty())
        assertFalse(File(dir, "repair_history.json").exists())
    }
}
