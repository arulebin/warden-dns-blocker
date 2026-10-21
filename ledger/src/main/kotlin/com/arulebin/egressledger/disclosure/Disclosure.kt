package com.arulebin.egressledger.disclosure

/**
 * How specifically a document discloses sharing with a third-party AI provider.
 * Ordered: a higher level is a strictly more specific disclosure.
 */
enum class DisclosureLevel(val code: String, val meaning: String) {
    L0_SILENT("L0", "No relevant mention"),
    L1_GENERIC("L1", "Shares with unnamed third parties / service providers"),
    L2_CATEGORICAL("L2", "Shares with third-party AI providers, none named"),
    L3_NAMED("L3", "Names the provider that was observed"),
}

enum class Verdict(val title: String) {
    UNDISCLOSED("Undisclosed egress"),
    LABEL_ONLY("Label-only disclosure"),
    LABEL_OMISSION("Label omission"),
    DISCLOSED_VAGUE("Disclosed but vague"),
    FULLY_DISCLOSED("Fully disclosed"),
}

/**
 * The pre-registered tie-breaking rule (fixed 2026-08-18, before any data is collected).
 * Observed egress is the fact; the two documents are claims tested against it.
 */
object VerdictGrid {
    fun decide(policy: DisclosureLevel, label: DisclosureLevel): Verdict {
        // The Data Safety form has no field that names a recipient, so L2/L3 cannot occur on it.
        require(label <= DisclosureLevel.L1_GENERIC) { "A Data Safety label cannot exceed L1, got $label" }
        val policySilent = policy == DisclosureLevel.L0_SILENT
        val labelSilent = label == DisclosureLevel.L0_SILENT
        return when {
            policySilent && labelSilent -> Verdict.UNDISCLOSED
            policySilent -> Verdict.LABEL_ONLY
            labelSilent -> Verdict.LABEL_OMISSION
            policy == DisclosureLevel.L3_NAMED -> Verdict.FULLY_DISCLOSED
            // L1 or L2 policy with an L1 label: disclosed, but the recipient is never named.
            else -> Verdict.DISCLOSED_VAGUE
        }
    }
}
