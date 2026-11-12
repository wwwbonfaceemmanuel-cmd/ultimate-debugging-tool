package com.ultimate.debugging.ultimate_debugging_tool

import java.io.IOException
import java.util.Locale

/** The pipe to a phone that is sitting in its bootloader. */
interface FastbootTransport {

    @Throws(IOException::class)
    fun write(bytes: ByteArray, timeoutMs: Long = 5000L)

    @Throws(IOException::class)
    fun read(
        maxBytes: Int = FastbootProtocol.MAX_PACKET,
        timeoutMs: Long = 30000L
    ): ByteArray

    @Throws(IOException::class)
    fun close()
}

/** A refusal or failure from the bootloader, with its own words. */
class FastbootException(message: String) : IOException(message)

/**
 * Talks to a phone in fastboot (bootloader) mode.
 *
 * Reading is harmless. Writing is not: a wrong image turns a phone into
 * a brick, so [flash] refuses a partition it does not recognise, checks
 * the image against the partition size the phone reports, and never
 * guesses which slot to write.
 */
class FastbootSession(private val transport: FastbootTransport) {

    companion object {
        private const val DEFAULT_CHUNK = 64L * 1024L
        private const val MAX_CHUNK = 4L * 1024L * 1024L

        /** Partitions this tool will write to. Nothing else. */
        val ALLOWED_PARTITIONS = listOf(
            "boot",
            "init_boot",
            "recovery",
            "dtbo",
            "vbmeta",
            "vendor_boot"
        )
    }

    class Partition(val name: String, val sizeBytes: Long) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "sizeBytes" to sizeBytes,
            "sizeText" to ShellSupport.formatBytes(sizeBytes)
        )
    }

    /** What the bootloader says about the phone. */
    class DeviceInfo(
        val product: String,
        val serial: String,
        val unlocked: Boolean?,
        val slots: Int,
        val currentSlot: String,
        val partitions: List<Partition>,
        val raw: Map<String, String>
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "product" to product,
            "serial" to serial,
            "unlocked" to unlocked,
            "unlockText" to when (unlocked) {
                true -> "Unlocked"
                false -> "Locked"
                null -> "Not reported"
            },
            "slots" to slots,
            "currentSlot" to currentSlot,
            "partitions" to partitions.map { it.toMap() },
            "variables" to raw
        )
    }

    // ---------- Reading ----------

    /** One variable, for example "product" or "current-slot". */
    @Throws(IOException::class)
    fun getvar(name: String): String {
        val response = command("getvar:$name")

        return when (response) {
            is FastbootProtocol.Response.Okay -> response.payload
            is FastbootProtocol.Response.Fail -> throw FastbootException(
                response.payload.ifBlank { "The phone refused to report $name." }
            )
            else -> throw FastbootException("The phone did not answer the $name request.")
        }
    }

    /** Every variable, as `getvar:all` prints them. */
    @Throws(IOException::class)
    fun getvarAll(): Map<String, String> {
        val variables = LinkedHashMap<String, String>()

        transport.write(FastbootProtocol.encodeCommand("getvar:all"))

        while (true) {
            val response = readResponse()

            when (response) {
                is FastbootProtocol.Response.Info -> {
                    val pair = FastbootProtocol.parseVariable(response.payload)
                        ?: continue
                    variables[pair.first] = pair.second
                }

                is FastbootProtocol.Response.Okay -> {
                    val pair = FastbootProtocol.parseVariable(response.payload)
                    if (pair != null) variables[pair.first] = pair.second
                    return variables
                }

                is FastbootProtocol.Response.Fail -> throw FastbootException(
                    response.payload.ifBlank { "The phone refused to list its variables." }
                )

                else -> return variables
            }
        }
    }

    /** Reads the phone's identity and partition table. */
    @Throws(IOException::class)
    fun deviceInfo(): DeviceInfo {
        val raw = getvarAll()

        val partitions = raw.entries
            .filter { it.key.startsWith("partition-size:") }
            .mapNotNull { entry ->
                val name = entry.key.removePrefix("partition-size:")
                val size = FastbootProtocol.parseHex(entry.value) ?: return@mapNotNull null
                if (name.isEmpty()) null else Partition(name, size)
            }
            .sortedBy { it.name }

        return DeviceInfo(
            product = raw["product"] ?: raw["variant"] ?: "Unknown",
            serial = raw["serialno"] ?: raw["serial"] ?: "",
            unlocked = when (raw["unlocked"]?.lowercase(Locale.US)) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> null
            },
            slots = raw["slot-count"]?.toIntOrNull() ?: 0,
            currentSlot = raw["current-slot"] ?: "",
            partitions = partitions,
            raw = raw
        )
    }

    // ---------- Writing ----------

    /**
     * Sends [image] to the phone's memory, without writing it anywhere.
     *
     * This is the "download:" half of fastboot. It changes nothing until
     * [flash] or [erase] is called.
     */
    @Throws(IOException::class)
    fun download(image: ByteArray) {
        if (image.isEmpty()) {
            throw FastbootException("The image file is empty.")
        }

        val response = command("download:" + FastbootProtocol.hex(image.size.toLong()))

        var chunk = when (response) {
            is FastbootProtocol.Response.Data -> {
                if (response.size in 1..MAX_CHUNK) response.size else DEFAULT_CHUNK
            }

            is FastbootProtocol.Response.Okay -> return
            is FastbootProtocol.Response.Fail -> throw FastbootException(
                response.payload.ifBlank { "The phone refused the image." }
            )

            else -> throw FastbootException("The phone did not accept the download.")
        }

        var offset = 0

        while (offset < image.size) {
            val count = minOf(chunk, (image.size - offset).toLong()).toInt()
            transport.write(image.copyOfRange(offset, offset + count), 30000L)
            offset += count

            if (offset < image.size) {
                when (val next = readResponse()) {
                    is FastbootProtocol.Response.Data -> {
                        if (next.size in 1..MAX_CHUNK) chunk = next.size
                    }

                    is FastbootProtocol.Response.Fail -> throw FastbootException(
                        next.payload.ifBlank { "The phone stopped the transfer." }
                    )

                    else -> Unit
                }
            }
        }

        val finished = readResponse()

        if (finished is FastbootProtocol.Response.Fail) {
            throw FastbootException(
                finished.payload.ifBlank { "The phone rejected the image." }
            )
        }
    }

    /**
     * Writes [image] to one partition.
     *
     * The guards, in order:
     *  - the partition must be one this tool knows;
     *  - the image must fit the size the phone reports for it;
     *  - the phone must answer with OKAY, or the call fails.
     */
    @Throws(IOException::class)
    fun flash(partition: String, image: ByteArray): String {
        if (!FastbootProtocol.isPartitionName(partition)) {
            throw FastbootException("That is not a partition name.")
        }

        if (!ALLOWED_PARTITIONS.contains(partition)) {
            throw FastbootException(
                "This tool writes only to: ${ALLOWED_PARTITIONS.joinToString(", ")}."
            )
        }

        if (image.isEmpty()) {
            throw FastbootException("The image file is empty.")
        }

        val reported = getvar("partition-size:$partition")
        val size = FastbootProtocol.parseHex(reported)

        if (size != null && size > 0 && image.size > size) {
            throw FastbootException(
                "The image is ${ShellSupport.formatBytes(image.size.toLong())} " +
                    "but the $partition partition is only " +
                    "${ShellSupport.formatBytes(size)}."
            )
        }

        download(image)

        val response = command("flash:$partition")

        return when (response) {
            is FastbootProtocol.Response.Okay ->
                response.payload.ifBlank { "The $partition partition was written." }

            is FastbootProtocol.Response.Fail -> throw FastbootException(
                response.payload.ifBlank { "The phone refused to write $partition." }
            )

            else -> throw FastbootException("The phone did not confirm writing $partition.")
        }
    }

    /** Erases one of the partitions this tool is allowed to write. */
    @Throws(IOException::class)
    fun erase(partition: String) {
        if (!ALLOWED_PARTITIONS.contains(partition)) {
            throw FastbootException(
                "This tool erases only: ${ALLOWED_PARTITIONS.joinToString(", ")}."
            )
        }

        when (val response = command("erase:$partition")) {
            is FastbootProtocol.Response.Okay -> Unit
            is FastbootProtocol.Response.Fail -> throw FastbootException(
                response.payload.ifBlank { "The phone refused to erase $partition." }
            )
            else -> throw FastbootException("The phone did not confirm erasing $partition.")
        }
    }

    /** Restarts the phone: "", "bootloader", "recovery" or "fastboot". */
    @Throws(IOException::class)
    fun reboot(target: String = "") {
        val command = when (target.trim().lowercase(Locale.US)) {
            "", "system" -> "reboot"
            else -> "reboot-" + target.trim().lowercase(Locale.US)
        }

        transport.write(FastbootProtocol.encodeCommand(command))

        // The phone drops off the bus, so an answer may never come.
        try {
            readResponse(3000L)
        } catch (ignored: IOException) {
            Unit
        }
    }

    /** An OEM command, for reading only. */
    @Throws(IOException::class)
    fun oem(command: String): String {
        if (command.isBlank()) throw FastbootException("An OEM command is required.")

        return when (val response = command("oem ${command.trim()}")) {
            is FastbootProtocol.Response.Okay ->
                response.payload.ifBlank { "The phone accepted the command." }

            is FastbootProtocol.Response.Fail -> throw FastbootException(
                response.payload.ifBlank { "The phone refused the command." }
            )

            else -> throw FastbootException("The phone did not answer the command.")
        }
    }

    fun close() {
        try {
            transport.close()
        } catch (ignored: IOException) {
            Unit
        }
    }

    // ---------- Plumbing ----------

    private fun command(text: String): FastbootProtocol.Response {
        transport.write(FastbootProtocol.encodeCommand(text))
        return readResponse()
    }

    private fun readResponse(timeoutMs: Long = 30000L): FastbootProtocol.Response {
        val bytes = transport.read(FastbootProtocol.MAX_PACKET, timeoutMs)

        if (bytes.isEmpty()) {
            throw FastbootException("The phone stopped answering. Check the cable.")
        }

        return FastbootProtocol.parse(bytes)
    }
}
