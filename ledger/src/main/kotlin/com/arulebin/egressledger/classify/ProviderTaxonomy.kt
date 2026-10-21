package com.arulebin.egressledger.classify

enum class ProviderKind { LLM, SPEECH, IMAGE, AGGREGATOR }

/**
 * Third-party AI providers, keyed by the API hostnames an app would put in its SNI.
 *
 * Entity-sensitive on purpose (PoliCheck [7]): every provider is its own entity, and
 * a host that matches none of them is NOT "AI" — it is unclassified, which includes
 * the app's own first-party backend. Merging the two would misread proxied calls.
 *
 * Seed list v0 (2026-09-18). Hostnames must be re-verified against each provider's
 * API documentation before corpus collection; the list is versioned with the ledger.
 */
enum class Provider(val displayName: String, val kind: ProviderKind, vararg val domains: String) {
    OPENAI("OpenAI", ProviderKind.LLM, "api.openai.com"),
    AZURE_OPENAI("Azure OpenAI", ProviderKind.LLM, "openai.azure.com"),
    ANTHROPIC("Anthropic", ProviderKind.LLM, "api.anthropic.com"),
    GOOGLE_GEMINI("Google Gemini API", ProviderKind.LLM, "generativelanguage.googleapis.com"),
    GOOGLE_VERTEX("Google Vertex AI", ProviderKind.LLM, "aiplatform.googleapis.com", "firebasevertexai.googleapis.com"),
    MISTRAL("Mistral AI", ProviderKind.LLM, "api.mistral.ai"),
    COHERE("Cohere", ProviderKind.LLM, "api.cohere.com", "api.cohere.ai"),
    GROQ("Groq", ProviderKind.LLM, "api.groq.com"),
    DEEPSEEK("DeepSeek", ProviderKind.LLM, "api.deepseek.com"),
    PERPLEXITY("Perplexity", ProviderKind.LLM, "api.perplexity.ai"),
    ELEVENLABS("ElevenLabs", ProviderKind.SPEECH, "api.elevenlabs.io"),
    DEEPGRAM("Deepgram", ProviderKind.SPEECH, "api.deepgram.com"),
    STABILITY("Stability AI", ProviderKind.IMAGE, "api.stability.ai"),
    REPLICATE("Replicate", ProviderKind.AGGREGATOR, "api.replicate.com"),
    HUGGING_FACE("Hugging Face", ProviderKind.AGGREGATOR, "api-inference.huggingface.co", "router.huggingface.co"),
    OPENROUTER("OpenRouter", ProviderKind.AGGREGATOR, "openrouter.ai"),
    TOGETHER("Together AI", ProviderKind.AGGREGATOR, "api.together.xyz"),
}

object ProviderTaxonomy {
    private val byDomain: Map<String, Provider> =
        Provider.entries.flatMap { p -> p.domains.map { it to p } }.toMap()

    /** Vertex AI's regional endpoints put the region in the same label: us-central1-aiplatform.googleapis.com. */
    private val vertexRegional = Regex("^[a-z0-9-]+-aiplatform\\.googleapis\\.com$")

    /** The provider [host] belongs to, or null when it is not a known third-party AI endpoint. */
    fun classify(host: String): Provider? {
        val h = host.lowercase().trimEnd('.')
        if (vertexRegional.matches(h)) return Provider.GOOGLE_VERTEX
        // Same suffix climb as Warden's Rules: test the host, then each parent domain.
        var name = h
        while (true) {
            byDomain[name]?.let { return it }
            val dot = name.indexOf('.')
            if (dot < 0) return null
            name = name.substring(dot + 1)
        }
    }
}
