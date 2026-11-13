package com.ultimate.debugging.ultimate_debugging_tool

import java.io.File
import java.io.IOException

/**
 * Ownership-verified reset flow for customer-brought phones.
 *
 * This does NOT bypass FRP, screen locks, or Google Account. It:
 * - records technician attestation that the owner is present + ID/receipt checked
 * - logs IMEI/serial + consent timestamp to a local consent file
 * - refuses any frp-partition flash or lock bypass
 * - provides official recovery deep-links for the technician to follow
 *
 * The actual reset (if the customer chooses) is always via the device's own
 * recovery or Settings — never via a bypass.
 */
class OwnershipVerifiedReset(
    private val runner: AdbShellRunner,
    private val filesDir: File
) {

    data class Attestation(
        val technicianName: String,
        val ownerPresent: Boolean,
        val idChecked: Boolean,
        val receiptChecked: Boolean,
        val notes: String
    )

    companion object {
        fun validateAttestation(a: Attestation): String? {
            if (a.technicianName.isBlank()) return "Technician name is required."
            if (!a.ownerPresent) return "Owner must be present — FRP bypass without owner is prohibited."
            if (!a.idChecked && !a.receiptChecked) return "Check at least ID or purchase receipt before proceeding."
            return null
        }

        val OFFICIAL_LINKS = linkedMapOf(
            "Google FRP help" to "https://support.google.com/android/answer/9459346",
            "Samsung Find My Mobile" to "https://findmymobile.samsung.com",
            "Xiaomi Mi Account unlock" to "https://account.xiaomi.com",
            "Oppo/Realme unlock" to "https://www.oppo.com/en/page/unlock"
        )

        fun refusedOperations(): List<String> = listOf(
            "flash frp partition",
            "remove locksettings.db",
            "bypass gatekeeper",
            "patch system to skip SetupWizard",
            "use paid bypass tool"
        )
    }

    @Throws(IOException::class)
    fun recordAttestation(attestation: Attestation): Map<String, Any?> {
        val error = validateAttestation(attestation)
        if (error != null) throw RepairException("ATTESTATION_INVALID", error)

        // Collect device identity for the consent receipt
        val imei = runner.shell("service call iphonesubinfo 1 2>/dev/null | cut -c 52-66 | tr -d '.[:space:]' 2>/dev/null", 8000L).output.trim()
        val serial = runner.shell("getprop ro.serialno 2>/dev/null", 5000L).output.trim()
        val ts = System.currentTimeMillis()

        val consentDir = File(filesDir, "ownership_consents")
        consentDir.mkdirs()
        val receipt = File(consentDir, "consent_$ts.txt")
        receipt.writeText(buildString {
            appendLine("OWNERSHIP-VERIFIED CONSENT RECEIPT")
            appendLine("Technician: ${attestation.technicianName}")
            appendLine("Owner present: ${attestation.ownerPresent}")
            appendLine("ID checked: ${attestation.idChecked}")
            appendLine("Receipt checked: ${attestation.receiptChecked}")
            appendLine("IMEI (redacted): ${imei.take(6)}****")
            appendLine("Serial: $serial")
            appendLine("Timestamp: $ts")
            appendLine("Notes: ${attestation.notes}")
            appendLine("Refused operations: ${refusedOperations().joinToString(", ")}")
            appendLine("Next: guide owner to official recovery links below.")
            OFFICIAL_LINKS.forEach { (k, v) -> appendLine("$k: $v") }
        })

        return mapOf(
            "receiptPath" to receipt.absolutePath,
            "receiptBytes" to receipt.length(),
            "timestamp" to ts,
            "links" to OFFICIAL_LINKS,
            "refused" to refusedOperations(),
            "advice" to "Do not flash frp. Ask owner to use their Google/Samsung/Mi account to unlock, or factory-reset via recovery with the account ready."
        )
    }

    @Throws(IOException::class)
    fun deviceIdentity(): Map<String, String> {
        val imei = runner.shell("service call iphonesubinfo 1 2>/dev/null | cut -c 52-66 | tr -d '.[:space:]' 2>/dev/null", 8000L).output.trim()
        val serial = runner.shell("getprop ro.serialno 2>/dev/null", 5000L).output.trim()
        val model = runner.shell("getprop ro.product.model 2>/dev/null", 5000L).output.trim()
        return mapOf("imeiRedacted" to imei.take(6) + "****", "serial" to serial, "model" to model)
    }
}
