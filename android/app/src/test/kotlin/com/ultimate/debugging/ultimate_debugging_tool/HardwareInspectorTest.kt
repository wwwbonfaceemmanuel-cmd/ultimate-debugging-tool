package com.ultimate.debugging.ultimate_debugging_tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareInspectorTest {

    private val getevent = """
        add device 1: /dev/input/event2
          name:     "sec_touchscreen"
          events:
            KEY (0001): KEY_HOME
            ABS (0003): ABS_MT_SLOT ABS_MT_POSITION_X ABS_MT_POSITION_Y
          input props:
            INPUT_PROP_DIRECT
        add device 2: /dev/input/event0
          name:     "gpio-keys"
          events:
            KEY (0001): KEY_VOLUMEUP KEY_VOLUMEDOWN KEY_POWER
    """.trimIndent()

    private val sensors = """
        Sensor List:
        0x00000000) BMI160 Accelerometer  | Bosch  | ver: 1 | type: android.sensor.accelerometer(1)
        0x00000001) BMI160 Gyroscope      | Bosch  | ver: 1 | type: android.sensor.gyroscope(4)
        0x00000002) Stk3x1x Light sensor  | Sensortek | ver: 1 | type: android.sensor.light(5)
    """.trimIndent()

    private val features = """
        feature:android.hardware.camera
        feature:android.hardware.camera.flash
        feature:android.hardware.wifi
        feature:android.hardware.bluetooth
        feature:android.hardware.nfc
        feature:android.hardware.fingerprint
    """.trimIndent()

    // ---------- Parsers ----------

    @Test
    fun findsTouchscreenAndButtonsSeparately() {
        val devices = HardwareInspector.parseInputDevices(getevent)

        assertEquals(2, devices.size)
        assertEquals("sec_touchscreen", devices[0].name)
        assertTrue("the touchscreen reports multitouch", devices[0].hasTouch)
        assertTrue(devices[1].hasKeys)
        assertFalse("the key pad is not a touchscreen", devices[1].hasTouch)
    }

    @Test
    fun listsSensorsWithoutTheirVendorText() {
        val names = HardwareInspector.parseSensorNames(sensors)

        assertEquals(
            listOf(
                "BMI160 Accelerometer",
                "BMI160 Gyroscope",
                "Stk3x1x Light sensor"
            ),
            names
        )
    }

    @Test
    fun readsFeatureNames() {
        val names = HardwareInspector.parseFeatures(features)

        assertTrue(names.contains("android.hardware.nfc"))
        assertTrue(names.contains("android.hardware.wifi"))
        assertFalse(names.any { it.startsWith("feature:") })
    }

    @Test
    fun readsScreenSizeAndDensity() {
        val values = HardwareInspector.parseKeyValues(
            "Physical size: 1080x2400\nPhysical density: 440\n"
        )

        assertEquals("1080x2400", values["physical size"])
        assertEquals("440", values["physical density"])
    }

    @Test
    fun countsOnlyRealPartitions() {
        val output = """
            major minor  #blocks  name

             179        0    61071360 mmcblk0
             179        1       65536 mmcblk0p1
        """.trimIndent()

        assertEquals(2, HardwareInspector.countPartitions(output))
    }

    // ---------- Battery verdicts ----------

    private fun battery(vararg pairs: Pair<String, String>) =
        HardwareInspector.batteryItems(linkedMapOf(*pairs))

    private fun statusOf(items: List<HardwareInspector.Item>, name: String) =
        items.first { it.name == name }.status

    @Test
    fun aCoolHealthyBatteryNeedsNothing() {
        val items = battery(
            "level" to "80",
            "health" to "2",
            "status" to "3",
            "temperature" to "310",
            "voltage" to "4100",
            "AC powered" to "false"
        )

        assertEquals("80 %", items.first { it.name == "Charge level" }.value)
        assertEquals(HardwareInspector.OK, statusOf(items, "Battery health"))
        assertEquals(HardwareInspector.OK, statusOf(items, "Battery temperature"))
    }

    @Test
    fun aWarmBatteryNeedsAttentionAndAHotOneFails() {
        val warm = battery("temperature" to "455", "health" to "2")
        val hot = battery("temperature" to "520", "health" to "2")

        assertEquals(HardwareInspector.ATTENTION, statusOf(warm, "Battery temperature"))
        assertEquals(HardwareInspector.FAIL, statusOf(hot, "Battery temperature"))
        assertTrue(statusOf(warm, "Battery temperature").isNotEmpty())
    }

    @Test
    fun aBatteryThatIsNotGoodNeedsAttention() {
        val items = battery("health" to "3")

        assertEquals("Overheated", items.first { it.name == "Battery health" }.value)
        assertEquals(HardwareInspector.ATTENTION, statusOf(items, "Battery health"))
        assertTrue(items.first { it.name == "Battery health" }.advice.isNotEmpty())
    }

    @Test
    fun showsHowThePhoneIsCharging() {
        val items = battery("status" to "2", "AC powered" to "true")

        assertEquals(
            "Charging from AC",
            items.first { it.name == "Charging" }.value
        )
    }

    @Test
    fun missingBatteryReadingsDoNotBreakTheReport() {
        val items = HardwareInspector.batteryItems(emptyMap())

        assertTrue(items.any { it.value == "Unavailable" })
    }

    // ---------- Storage ----------

    @Test
    fun warnsWhenStorageIsNearlyFull() {
        val output = """
            Filesystem      1K-blocks     Used Available Use% Mounted on
            /dev/block/dm-5  100000000 97000000   3000000  97% /data
        """.trimIndent()

        val items = HardwareInspector.storageItems(output)
        val free = items.first { it.name == "Free space" }

        assertEquals(HardwareInspector.FAIL, free.status)
        assertTrue(free.advice.isNotEmpty())
    }

    @Test
    fun quietAboutStorageThatIsFine() {
        val output = """
            Filesystem      1K-blocks     Used Available Use% Mounted on
            /dev/block/dm-5  100000000 30000000  70000000  30% /data
        """.trimIndent()

        val items = HardwareInspector.storageItems(output)

        assertEquals(HardwareInspector.OK, items.first { it.name == "Free space" }.status)
    }

    // ---------- Radios ----------

    @Test
    fun readsRadioState() {
        val items = HardwareInspector.radioItems(
            "Wi-Fi is enabled",
            "enabled: false, state: 12",
            "NFC service: running",
            "mServiceState=0 home",
            HardwareInspector.parseFeatures(features)
        )

        val byName = items.associateBy { it.name }

        assertEquals("On", byName["Wi-Fi"]!!.value)
        assertEquals("Off", byName["Bluetooth"]!!.value)
        assertEquals("Fitted", byName["NFC"]!!.value)
        assertEquals("Registered", byName["Mobile network"]!!.value)
        assertEquals("Fitted", byName["Fingerprint sensor"]!!.value)
    }

    // ---------- Whole check-up ----------

    private fun phone(): ScriptedPhone {
        return ScriptedPhone { command ->
            when {
                command.startsWith("getprop") -> """
                    [ro.product.manufacturer]: [samsung]
                    [ro.product.model]: [SM-A125F]
                    [ro.build.version.release]: [12]
                    [ro.build.version.sdk]: [31]
                    [ro.build.version.security_patch]: [2024-01-01]
                    [ro.boot.flash.locked]: [1]
                    [ro.boot.verifiedbootstate]: [green]
                """.trimIndent()

                command == "pm list features" -> features
                command.startsWith("wm size") -> "Physical size: 1080x2400\nPhysical density: 440\n"
                command.startsWith("getevent") -> getevent
                command.startsWith("dumpsys sensorservice") -> sensors
                command.startsWith("dumpsys battery") -> """
                    AC powered: false
                    USB powered: true
                    status: 2
                    health: 2
                    level: 74
                    voltage: 4200
                    temperature: 320
                """.trimIndent()

                command.startsWith("df -k") -> """
                    Filesystem      1K-blocks     Used Available Use% Mounted on
                    /dev/block/dm-5  100000000 40000000  60000000  40% /data
                """.trimIndent()

                command.startsWith("cat /proc/partitions") -> """
                    major minor  #blocks  name

                     179        0    61071360 mmcblk0
                """.trimIndent()

                command.startsWith("dumpsys wifi") -> "Wi-Fi is enabled"
                command.startsWith("dumpsys bluetooth_manager") -> "enabled: true"
                command.startsWith("dumpsys nfc") -> "NFC service: running"
                command.startsWith("dumpsys telephony.registry") -> "mServiceState=0"
                command.startsWith("dumpsys media.camera") -> "Camera service running"
                else -> null
            }
        }
    }

    @Test
    fun checksEveryPartOfThePhone() {
        val checkup = HardwareInspector(phone()).run()
        val titles = checkup.sections.map { it.title }

        assertTrue(titles.contains("Identity"))
        assertTrue(titles.contains("Touch and input"))
        assertTrue(titles.contains("Battery"))
        assertTrue(titles.contains("Storage"))
        assertTrue(titles.contains("Radios"))
        assertTrue(titles.contains("Boot and firmware"))

        val values = checkup.sections
            .flatMap { it.items }
            .associate { it.name to it.value }

        assertEquals("samsung SM-A125F", values["Phone"])
        assertEquals("1080x2400", values["Screen size"])
        assertEquals("sec_touchscreen", values["Touchscreen"])
        assertEquals("3", values["Sensors found"])
        assertEquals("Locked", values["Bootloader"])
        assertEquals("74 %", values["Charge level"])
    }

    @Test
    fun unlockedBootloaderIsFlagged() {
        val locked = ScriptedPhone { null }
        val checkup = HardwareInspector(locked).run()

        // No property at all: the report says so instead of guessing.
        val boot = checkup.sections.first { it.title == "Boot and firmware" }
        assertEquals("Not reported", boot.items.first { it.name == "Bootloader" }.value)
    }

    @Test
    fun theReportOnlyReads() {
        val phone = phone()

        HardwareInspector(phone).run()

        val forbidden = Regex(
            "\\b(rm|reboot|wipe|dd|pm (clear|uninstall|disable|enable)|" +
                "am force-stop|settings put|svc )\\b"
        )

        for (command in phone.commands) {
            assertFalse(
                "The check-up must not change the phone: $command",
                forbidden.containsMatchIn(command)
            )
        }
    }

    @Test
    fun aSilentPhoneStillProducesAReport() {
        val checkup = HardwareInspector(ScriptedPhone { null }).run()

        assertTrue(checkup.totalCount > 0)
        assertTrue(checkup.summary().isNotEmpty())
        assertTrue(checkup.report().contains("HARDWARE CHECK"))
        assertTrue(
            "missing readings must be flagged, not passed silently",
            checkup.attentionCount > 0
        )
    }

    @Test
    fun physicalChecksCoverTheWholePhone() {
        val ids = HardwareInspector.MANUAL_CHECKS.map { it.id }

        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.contains("screen"))
        assertTrue(ids.contains("touch"))
        assertTrue(ids.contains("charging"))
        assertTrue(
            HardwareInspector.MANUAL_CHECKS.all { it.instruction.length > 20 }
        )
    }

    @Test
    fun eachReadingCarriesItsVerdict() {
        val checkup = HardwareInspector(phone()).run()

        for (section in checkup.sections) {
            for (item in section.items) {
                assertTrue(
                    "Unknown verdict for ${item.name}: ${item.status}",
                    item.status in listOf(
                        HardwareInspector.OK,
                        HardwareInspector.ATTENTION,
                        HardwareInspector.INFO,
                        HardwareInspector.FAIL
                    )
                )
            }
        }
    }
}
