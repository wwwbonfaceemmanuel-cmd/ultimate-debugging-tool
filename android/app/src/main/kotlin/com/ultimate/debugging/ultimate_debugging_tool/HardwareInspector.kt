package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.Locale

/**
 * A hardware check-up of the CONNECTED phone.
 *
 * It has two halves:
 *  - readings the phone reports about itself over ADB (screen, input
 *    devices, sensors, battery, radios, storage, partitions, boot state);
 *  - physical checks the technician performs on the phone itself,
 *    because a phone cannot see another phone's screen or hear its
 *    speaker.
 *
 * Every command here only reads. Nothing is changed on the phone.
 */
class HardwareInspector(private val runner: AdbShellRunner) {

    class ManualCheck(
        val id: String,
        val title: String,
        val instruction: String
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id,
            "title" to title,
            "instruction" to instruction
        )
    }

    class Item(
        val name: String,
        val value: String,
        val status: String,
        val advice: String = ""
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "value" to value,
            "status" to status,
            "advice" to advice
        )
    }

    class Section(val title: String, val items: List<Item>) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "title" to title,
            "items" to items.map { it.toMap() }
        )
    }

    /** One input device as listed by `getevent -lp`. */
    class InputDevice(
        val name: String,
        val hasTouch: Boolean,
        val hasKeys: Boolean
    )

    /** All readings, plus the list of physical checks. */
    class Checkup(val sections: List<Section>) {

        val totalCount: Int
            get() = sections.sumOf { it.items.size }

        val attentionCount: Int
            get() = sections.sumOf { section ->
                section.items.count {
                    it.status == ATTENTION || it.status == FAIL
                }
            }

        fun summary(): String = when {
            totalCount == 0 -> "No readings came back from the phone."
            attentionCount == 0 -> "$totalCount readings, none needing attention."
            else -> "$totalCount readings, $attentionCount needing attention."
        }

        fun report(): String {
            val builder = ReportBuilder("HARDWARE CHECK (CONNECTED PHONE)")

            for (section in sections) {
                builder.section(section.title)

                for (item in section.items) {
                    val advice = if (item.advice.isEmpty()) "" else " - ${item.advice}"
                    builder.line(item.name, "${item.value} [${item.status}]$advice")
                }
            }

            builder.section("Physical checks").bullets(
                MANUAL_CHECKS.map { "${it.title}: ${it.instruction}" }
            )

            return builder.toString()
        }

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "sections" to sections.map { it.toMap() },
            "manualChecks" to MANUAL_CHECKS.map { it.toMap() },
            "totalCount" to totalCount,
            "attentionCount" to attentionCount,
            "summary" to summary(),
            "report" to report()
        )
    }

    companion object {

        const val OK = "OK"
        const val ATTENTION = "ATTENTION"
        const val INFO = "INFO"
        const val FAIL = "FAIL"

        /** Battery temperatures, in degrees Celsius. */
        private const val WARM_C = 42.0
        private const val HOT_C = 50.0

        /** Free-space share below which storage needs attention. */
        private const val LOW_STORAGE_PERCENT = 10

        private const val MAX_SENSORS = 12

        /** The checks that need a human in front of the phone. */
        val MANUAL_CHECKS: List<ManualCheck> = listOf(
            ManualCheck(
                "screen",
                "Screen and pixels",
                "Open a white picture, then a black one. Look for dead " +
                    "pixels, bright spots, lines and uneven colour."
            ),
            ManualCheck(
                "touch",
                "Touch across the screen",
                "Press every digit of the dialler and all four corners. " +
                    "Every press must respond in the same place."
            ),
            ManualCheck(
                "speaker",
                "Loudspeaker",
                "Play a song or a ringtone at full volume. Listen for " +
                    "silence, crackle or distortion."
            ),
            ManualCheck(
                "microphone",
                "Microphones",
                "Record a voice memo, play it back, and also try a call on " +
                    "speaker. The voice must be clear."
            ),
            ManualCheck(
                "cameras",
                "Cameras",
                "Switch through every lens, front and back, and take one " +
                    "photo with each. Look for blur, spots and focus faults."
            ),
            ManualCheck(
                "vibration",
                "Vibration",
                "Turn on vibration and ring the phone from another phone. " +
                    "The vibration must be felt, not heard only."
            ),
            ManualCheck(
                "buttons",
                "Buttons",
                "Press power, both volume keys and any extra key. Each must " +
                    "click and respond on screen."
            ),
            ManualCheck(
                "charging",
                "Charging port",
                "Plug in the charger. The phone must report charging. Wiggle " +
                    "the cable gently to test the port and check for heat."
            )
        )

        // ---------- Pure parsers (unit tested) ----------

        /** Reads the names out of `pm list features`. */
        fun parseFeatures(output: String): List<String> = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("feature:") }
            .map { it.removePrefix("feature:").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        /**
         * Reads `getevent -lp` into one entry per input device.
         *
         * A device is a touchscreen when it reports multi-touch axes or
         * is marked as a direct input device; it has buttons when it
         * reports key capabilities.
         */
        fun parseInputDevices(output: String): List<InputDevice> {
            val devices = ArrayList<InputDevice>()

            var name = ""
            var touch = false
            var keys = false
            var open = false

            fun flush() {
                if (!open) return
                devices.add(
                    InputDevice(name.ifEmpty { "Input device" }, touch, keys)
                )
            }

            for (raw in output.lineSequence()) {
                val line = raw.trimEnd()
                val trimmed = line.trimStart()

                when {
                    line.startsWith("add device") -> {
                        flush()
                        open = true
                        name = ""
                        touch = false
                        keys = false
                    }

                    trimmed.startsWith("name:") ->
                        name = trimmed.substringAfter("name:")
                            .trim()
                            .trim('"')

                    line.contains("ABS_MT_POSITION") ||
                        line.contains("INPUT_PROP_DIRECT") -> touch = true

                    trimmed.startsWith("KEY (") -> keys = true
                }
            }

            flush()

            return devices
        }

        private val SENSOR_LINE =
            Regex("^\\s*0x([0-9a-fA-F]{8})\\)\\s*(.+?)\\s*\\|.*$")

        /** Reads the sensor names out of `dumpsys sensorservice`. */
        fun parseSensorNames(output: String): List<String> = output.lineSequence()
            .mapNotNull { SENSOR_LINE.find(it)?.groupValues?.get(2)?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

        /** Reads "key: value" lines such as `wm size` / `wm density`. */
        fun parseKeyValues(output: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()

            for (line in output.lineSequence()) {
                val index = line.indexOf(':')
                if (index <= 0) continue

                val key = line.substring(0, index).trim().lowercase(Locale.US)
                val value = line.substring(index + 1).trim()

                if (key.isNotEmpty() && value.isNotEmpty()) {
                    values[key] = value
                }
            }

            return values
        }

        /** Reads `/proc/partitions` into the number of real partitions. */
        fun countPartitions(output: String): Int = output.lineSequence()
            .count { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.size == 4 && parts[0].toIntOrNull() != null
            }

        /**
         * The battery readings, with a verdict for each.
         *
         * Cool, healthy and nearly full is dull; anything else gets a
         * sentence the technician can act on.
         */
        fun batteryItems(values: Map<String, String>): List<Item> {
            val items = ArrayList<Item>()

            val level = values["level"]?.toIntOrNull()
            val temperature = values["temperature"]
                ?.toDoubleOrNull()
                ?.div(10.0)
            val voltage = values["voltage"]?.toIntOrNull()
            val counter = values["charge counter"]?.toLongOrNull()

            val health = TargetDiagnostics.batteryHealth(values["health"])
            val charging = TargetDiagnostics.batteryStatus(values["status"])
            val plugged = listOfNotNull(
                "AC".takeIf { values["AC powered"] == "true" },
                "USB".takeIf { values["USB powered"] == "true" },
                "Wireless".takeIf { values["Wireless powered"] == "true" },
                "Dock".takeIf { values["Dock powered"] == "true" }
            )

            items.add(
                Item(
                    "Charge level",
                    level?.let { "$it %" } ?: "Unavailable",
                    INFO
                )
            )

            items.add(
                Item(
                    "Charging",
                    if (plugged.isEmpty()) charging else "$charging from ${plugged.joinToString(", ")}",
                    INFO
                )
            )

            items.add(
                Item(
                    "Battery health",
                    health,
                    if (health == "Good") OK else ATTENTION,
                    if (health == "Good") {
                        ""
                    } else {
                        "A battery that is not \"Good\" may need replacing."
                    }
                )
            )

            if (temperature != null) {
                val status = when {
                    temperature >= HOT_C -> FAIL
                    temperature >= WARM_C -> ATTENTION
                    else -> OK
                }

                items.add(
                    Item(
                        "Battery temperature",
                        String.format(Locale.US, "%.1f °C", temperature),
                        status,
                        when (status) {
                            FAIL -> "Very hot. Stop charging and let it cool before continuing."
                            ATTENTION -> "Warm. Keep an eye on it while charging."
                            else -> ""
                        }
                    )
                )
            } else {
                items.add(Item("Battery temperature", "Unavailable", INFO))
            }

            items.add(
                Item(
                    "Battery voltage",
                    voltage?.let { String.format(Locale.US, "%.3f V", it / 1000.0) }
                        ?: "Unavailable",
                    INFO
                )
            )

            if (counter != null && counter > 0) {
                items.add(
                    Item("Charge counter", ShellSupport.formatBytes(counter * 1000L), INFO)
                )
            }

            return items
        }

        /** The storage reading, with a verdict when space is short. */
        fun storageItems(output: String): List<Item> {
            val volume = TargetDiagnostics.parseDf(output).firstOrNull()
                ?: return listOf(Item("Storage", "Unavailable", INFO))

            val freePercent = if (volume.totalKb > 0) {
                (volume.freeKb * 100 / volume.totalKb).toInt()
            } else {
                0
            }

            val status = when {
                freePercent < 5 -> FAIL
                freePercent < LOW_STORAGE_PERCENT -> ATTENTION
                else -> OK
            }

            return listOf(
                Item("Total space", ShellSupport.formatBytes(volume.totalKb * 1024), INFO),
                Item(
                    "Used space",
                    "${ShellSupport.formatBytes(volume.usedKb * 1024)} (${volume.usedPercent} %)",
                    INFO
                ),
                Item(
                    "Free space",
                    "${ShellSupport.formatBytes(volume.freeKb * 1024)} ($freePercent %)",
                    status,
                    if (status == OK) {
                        ""
                    } else {
                        "Storage is nearly full. Clear caches or move files off the phone."
                    }
                )
            )
        }

        private fun stateWord(output: String, on: String, off: String): String {
            val lower = output.lowercase(Locale.US)

            return when {
                lower.contains(on.lowercase(Locale.US)) -> "On"
                lower.contains(off.lowercase(Locale.US)) -> "Off"
                else -> "Not reported"
            }
        }

        /** Wi-Fi, Bluetooth, NFC and the mobile network, as the phone sees them. */
        fun radioItems(
            wifi: String,
            bluetooth: String,
            nfc: String,
            telephony: String,
            features: List<String>
        ): List<Item> {
            val items = ArrayList<Item>()

            fun supported(feature: String) = features.any {
                it.equals(feature, ignoreCase = true)
            }

            items.add(
                Item(
                    "Wi-Fi",
                    if (!supported("android.hardware.wifi")) {
                        "Not reported by this phone"
                    } else {
                        stateWord(wifi, "Wi-Fi is enabled", "Wi-Fi is disabled")
                    },
                    INFO
                )
            )

            items.add(
                Item(
                    "Bluetooth",
                    if (!supported("android.hardware.bluetooth")) {
                        "Not reported by this phone"
                    } else {
                        stateWord(bluetooth, "enabled: true", "enabled: false")
                    },
                    INFO
                )
            )

            items.add(
                Item(
                    "NFC",
                    when {
                        !supported("android.hardware.nfc") -> "Not fitted"
                        ShellSupport.looksLikeError(nfc) -> "Fitted, service quiet"
                        else -> "Fitted"
                    },
                    INFO
                )
            )

            val mobile = when {
                telephony.contains("mServiceState=0") -> "Registered"
                telephony.contains("mServiceState=1") -> "Not registered"
                telephony.contains("mServiceState=3") -> "Registration denied"
                telephony.isBlank() -> "Not reported"
                else -> "Reported"
            }

            items.add(Item("Mobile network", mobile, INFO))

            items.add(
                Item(
                    "GNSS (location)",
                    if (supported("android.hardware.location.gps")) "Fitted" else "Not fitted",
                    INFO
                )
            )

            items.add(
                Item(
                    "Fingerprint sensor",
                    if (features.any { it.contains("fingerprint", true) }) "Fitted" else "Not fitted",
                    INFO
                )
            )

            return items
        }

        /** The thermal service, if it answers. */
        fun thermalItem(output: String): Item {
            val line = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }

            return if (line == null) {
                Item("Thermal service", "No throttling reported", INFO)
            } else {
                Item(
                    "Thermal service",
                    line.take(160),
                    INFO
                )
            }
        }
    }

    /**
     * Collects every reading in one pass.
     *
     * The output of large services is cut down on the phone itself, so
     * a slow link does not have to carry megabytes.
     */
    @Throws(IOException::class)
    fun run(): Checkup {
        val data = ShellSupport.runSections(
            runner,
            linkedMapOf(
                "props" to "getprop | grep -E " +
                    "'ro.product.(model|manufacturer)|" +
                    "ro.build.version.(release|sdk|security_patch)|" +
                    "ro.boot.(flash.locked|verifiedbootstate|slot)'",
                "features" to "pm list features",
                "display" to "wm size; wm density",
                "input" to "getevent -lp",
                "sensors" to "dumpsys sensorservice | head -n 120",
                "battery" to "dumpsys battery",
                "thermal" to "dumpsys thermalservice | grep -m 2 -i 'thermal status'",
                "camera" to "dumpsys media.camera | head -n 40",
                "storage" to "df -k /data",
                "partitions" to "cat /proc/partitions | head -n 40",
                "wifi" to "dumpsys wifi | grep -m 4 -i -e 'wi-fi is' -e 'current ssid'",
                "bluetooth" to "dumpsys bluetooth_manager | grep -m 4 -i -e 'enabled:' -e 'state:'",
                "nfc" to "dumpsys nfc | head -n 20",
                "telephony" to "dumpsys telephony.registry | grep -m 4 -e 'mServiceState'"
            ),
            45000L
        )

        val properties = ShellSupport.parseProperties(data["props"].orEmpty())
        val features = parseFeatures(data["features"].orEmpty())

        val model = listOfNotNull(
            properties["ro.product.manufacturer"]?.takeIf { it.isNotBlank() },
            properties["ro.product.model"]?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { "Unavailable" }

        val identity = listOf(
            Item("Phone", model, INFO),
            Item(
                "Android version",
                properties["ro.build.version.release"]?.ifBlank { null } ?: "Unavailable",
                INFO
            ),
            Item(
                "API level",
                properties["ro.build.version.sdk"]?.ifBlank { null } ?: "Unavailable",
                INFO
            ),
            Item(
                "Security patch",
                properties["ro.build.version.security_patch"]?.ifBlank { null } ?: "Unavailable",
                INFO
            )
        )

        val displayValues = parseKeyValues(data["display"].orEmpty())

        val display = listOf(
            Item(
                "Screen size",
                displayValues["override size"]
                    ?: displayValues["physical size"]
                    ?: "Unavailable",
                INFO
            ),
            Item(
                "Screen density",
                displayValues["override density"]
                    ?: displayValues["physical density"]
                    ?: "Unavailable",
                INFO
            )
        )

        val inputs = parseInputDevices(data["input"].orEmpty())
        val touchDevices = inputs.filter { it.hasTouch }.map { it.name }
        val keyDevices = inputs.filter { it.hasKeys }

        val inputItems = ArrayList<Item>()
        inputItems.add(
            Item(
                "Input devices",
                if (inputs.isEmpty()) "Unavailable" else inputs.size.toString(),
                if (inputs.isEmpty()) ATTENTION else OK,
                if (inputs.isEmpty()) {
                    "The phone did not list its input devices. Try again after a restart."
                } else {
                    ""
                }
            )
        )
        inputItems.add(
            Item(
                "Touchscreen",
                if (touchDevices.isEmpty()) "Not reported" else touchDevices.joinToString(", "),
                if (touchDevices.isEmpty()) ATTENTION else OK,
                if (touchDevices.isEmpty()) {
                    "No touchscreen was reported. Check the screen by touch as well."
                } else {
                    ""
                }
            )
        )
        inputItems.add(
            Item(
                "Devices with buttons",
                if (keyDevices.isEmpty()) "None reported" else keyDevices.joinToString(", ") { it.name },
                INFO
            )
        )

        val sensors = parseSensorNames(data["sensors"].orEmpty())
        val sensorItems = ArrayList<Item>()
        sensorItems.add(
            Item(
                "Sensors found",
                sensors.size.toString(),
                if (sensors.isEmpty()) ATTENTION else OK,
                if (sensors.isEmpty()) {
                    "No sensors were listed. Restart the phone and read again."
                } else {
                    ""
                }
            )
        )
        sensors.take(MAX_SENSORS).forEach { name ->
            sensorItems.add(Item(name, "Present", INFO))
        }

        val cameraText = data["camera"].orEmpty()
        val camera = listOf(
            Item(
                "Camera hardware",
                if (features.any { it.startsWith("android.hardware.camera") }) "Fitted" else "Not reported",
                INFO
            ),
            Item(
                "Camera service",
                if (ShellSupport.looksLikeError(cameraText)) "Not reporting" else "Reporting",
                INFO,
                if (ShellSupport.looksLikeError(cameraText)) {
                    "Check the cameras by hand; the service gave no answer."
                } else {
                    ""
                }
            )
        )

        val partitions = countPartitions(data["partitions"].orEmpty())

        val storage = listOf(
            Item(
                "Partition table",
                if (partitions == 0) "Unavailable" else "$partitions entries",
                INFO
            )
        ) + storageItems(data["storage"].orEmpty())

        val locked = when (properties["ro.boot.flash.locked"]?.trim()) {
            "1" -> "Locked"
            "0" -> "Unlocked"
            else -> "Not reported"
        }

        val boot = listOf(
            Item(
                "Bootloader",
                locked,
                if (locked == "Unlocked") ATTENTION else INFO,
                if (locked == "Unlocked") {
                    "An unlocked bootloader is a security risk and voids some warranties. " +
                        "The owner must decide whether to lock it again."
                } else {
                    ""
                }
            ),
            Item(
                "Verified boot",
                properties["ro.boot.verifiedbootstate"]?.ifBlank { null } ?: "Not reported",
                INFO
            ),
            Item(
                "Active slot",
                properties["ro.boot.slot"]?.ifBlank { null } ?: "Not reported",
                INFO
            )
        )

        return Checkup(
            listOf(
                Section("Identity", identity),
                Section("Display", display),
                Section("Touch and input", inputItems),
                Section("Sensors", sensorItems),
                Section("Battery", batteryItems(TargetDiagnostics.parseKeyValues(data["battery"].orEmpty()))),
                Section("Storage", storage),
                Section("Cameras", camera),
                Section(
                    "Radios",
                    radioItems(
                        data["wifi"].orEmpty(),
                        data["bluetooth"].orEmpty(),
                        data["nfc"].orEmpty(),
                        data["telephony"].orEmpty(),
                        features
                    )
                ),
                Section("Boot and firmware", boot),
                Section("Thermal", listOf(thermalItem(data["thermal"].orEmpty())))
            )
        )
    }
}
