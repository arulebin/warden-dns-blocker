package com.arulebin.egressledger

import com.arulebin.egressledger.classify.Provider
import com.arulebin.egressledger.classify.ProviderTaxonomy
import com.arulebin.egressledger.disclosure.DisclosureLevel.L0_SILENT
import com.arulebin.egressledger.disclosure.DisclosureLevel.L1_GENERIC
import com.arulebin.egressledger.disclosure.DisclosureLevel.L2_CATEGORICAL
import com.arulebin.egressledger.disclosure.DisclosureLevel.L3_NAMED
import com.arulebin.egressledger.disclosure.PolicyScanner
import com.arulebin.egressledger.disclosure.Verdict
import com.arulebin.egressledger.disclosure.VerdictGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClassifyAndDiscloseTest {

    // ---- endpoint taxonomy ----

    @Test fun `classifies provider hosts and their subdomains`() {
        assertEquals(Provider.OPENAI, ProviderTaxonomy.classify("api.openai.com"))
        assertEquals(Provider.AZURE_OPENAI, ProviderTaxonomy.classify("contoso-eu.openai.azure.com"))
        assertEquals(Provider.GOOGLE_VERTEX, ProviderTaxonomy.classify("us-central1-aiplatform.googleapis.com"))
        assertEquals(Provider.ELEVENLABS, ProviderTaxonomy.classify("API.ElevenLabs.io."))
    }

    @Test fun `does not treat lookalikes or first-party hosts as AI providers`() {
        assertNull(ProviderTaxonomy.classify("notapi.openai.com.evil.example"))
        assertNull(ProviderTaxonomy.classify("openai.com"))            // marketing site, not the API
        assertNull(ProviderTaxonomy.classify("www.googleapis.com"))
        assertNull(ProviderTaxonomy.classify("api.myapp-backend.in"))  // first-party proxy: invisible by design
    }

    // ---- policy scanner (entity-sensitive) ----

    private val named = "We use OpenAI's API to generate captions for your photos. Data is deleted after 30 days."
    private val categorical = "Your prompts are processed by third-party AI providers to produce responses."
    private val generic = "We may share information with trusted service providers who help us operate the app."
    private val silent = "We care about your privacy. You can delete your account at any time."

    @Test fun `scores each specificity level with a quoted sentence`() {
        val f = PolicyScanner.scan(named, Provider.OPENAI)
        assertEquals(L3_NAMED, f.level)
        assertEquals("We use OpenAI's API to generate captions for your photos.", f.evidence)
        assertEquals(L2_CATEGORICAL, PolicyScanner.scan(categorical, Provider.OPENAI).level)
        assertEquals(L1_GENERIC, PolicyScanner.scan(generic, Provider.OPENAI).level)
        assertEquals(L0_SILENT, PolicyScanner.scan(silent, Provider.OPENAI).level)
    }

    @Test fun `naming one provider does not disclose a flow to another`() {
        // The policy names OpenAI, but the traffic went to Anthropic: not L3 for Anthropic.
        assertEquals(L0_SILENT, PolicyScanner.scan(named, Provider.ANTHROPIC).level)
    }

    @Test fun `a lowercase common word is not a provider name`() {
        val text = "We replicate your data across two regions for durability."
        assertEquals(L0_SILENT, PolicyScanner.scan(text, Provider.REPLICATE).level)
    }

    // ---- the pre-registered verdict grid ----

    @Test fun `verdict grid matches the rule fixed on 2026-08-18`() {
        assertEquals(Verdict.UNDISCLOSED, VerdictGrid.decide(L0_SILENT, L0_SILENT))
        assertEquals(Verdict.LABEL_ONLY, VerdictGrid.decide(L0_SILENT, L1_GENERIC))
        assertEquals(Verdict.LABEL_OMISSION, VerdictGrid.decide(L3_NAMED, L0_SILENT))
        assertEquals(Verdict.LABEL_OMISSION, VerdictGrid.decide(L1_GENERIC, L0_SILENT))
        assertEquals(Verdict.DISCLOSED_VAGUE, VerdictGrid.decide(L1_GENERIC, L1_GENERIC))
        assertEquals(Verdict.DISCLOSED_VAGUE, VerdictGrid.decide(L2_CATEGORICAL, L1_GENERIC))
        assertEquals(Verdict.FULLY_DISCLOSED, VerdictGrid.decide(L3_NAMED, L1_GENERIC))
    }

    @Test fun `a Data Safety label can never be scored above L1`() {
        assertFailsWith<IllegalArgumentException> { VerdictGrid.decide(L0_SILENT, L3_NAMED) }
    }
}
