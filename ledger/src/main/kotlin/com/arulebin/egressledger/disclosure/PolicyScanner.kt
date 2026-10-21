package com.arulebin.egressledger.disclosure

import com.arulebin.egressledger.classify.Provider

/** A disclosure level plus the sentence that justifies it — a verdict without a quote is not accepted. */
data class PolicyFinding(val level: DisclosureLevel, val evidence: String?)

/**
 * Stage 1 of policy interpretation: deterministic keyword/alias matching.
 * Stage 2 (LLM-assisted extraction, validated against a manually annotated
 * 20-30% sample) runs only on policies this stage scores L0 or L1, to catch
 * paraphrases the patterns miss.
 *
 * Entity-sensitive: L3 is awarded only when the policy names the provider that was
 * actually observed. Naming OpenAI does not disclose a flow to Anthropic.
 */
object PolicyScanner {
    private val aliases: Map<Provider, List<String>> = mapOf(
        Provider.OPENAI to listOf("OpenAI", "ChatGPT", "GPT-4", "GPT-4o", "GPT-5", "Whisper API", "DALL-E", "DALL·E"),
        Provider.AZURE_OPENAI to listOf("Azure OpenAI"),
        Provider.ANTHROPIC to listOf("Anthropic", "Claude"),
        Provider.GOOGLE_GEMINI to listOf("Gemini", "Google AI"),
        Provider.GOOGLE_VERTEX to listOf("Vertex AI", "Gemini", "Firebase AI Logic"),
        Provider.MISTRAL to listOf("Mistral"),
        Provider.COHERE to listOf("Cohere"),
        Provider.GROQ to listOf("Groq"),
        Provider.DEEPSEEK to listOf("DeepSeek"),
        Provider.PERPLEXITY to listOf("Perplexity"),
        Provider.ELEVENLABS to listOf("ElevenLabs", "Eleven Labs"),
        Provider.DEEPGRAM to listOf("Deepgram"),
        Provider.STABILITY to listOf("Stability AI", "Stable Diffusion"),
        Provider.REPLICATE to listOf("Replicate"),
        Provider.HUGGING_FACE to listOf("Hugging Face", "HuggingFace"),
        Provider.OPENROUTER to listOf("OpenRouter"),
        Provider.TOGETHER to listOf("Together AI"),
    )

    private val categorical = Regex(
        "\\b(third[- ]party|external|outside)\\s+(AI|artificial intelligence|machine[- ]learning|" +
            "large language model|LLM|generative AI)\\s*(model\\s+)?(providers?|vendors?|partners?|services?|processors?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val generic = Regex(
        "\\b(service providers?|sub-?processors?|third[- ]part(y|ies)|vendors?|partners?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Data-sharing verbs, so a sentence that merely mentions "partners" in passing does not count. */
    private val sharing = Regex(
        "\\b(share[sd]?|sharing|disclose[sd]?|transfer(s|red)?|send[s]?|sent|provide[sd]? to|process(ed|es)? by)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun scan(policyText: String, observed: Provider): PolicyFinding {
        val sentences = splitSentences(policyText)
        // Case-sensitive: brand names are capitalised, and "we replicate your data" must not read as Replicate.
        val names = aliases[observed].orEmpty().map { Regex("\\b${Regex.escape(it)}\\b") }

        sentences.firstOrNull { s -> names.any { it.containsMatchIn(s) } }
            ?.let { return PolicyFinding(DisclosureLevel.L3_NAMED, it) }
        sentences.firstOrNull { categorical.containsMatchIn(it) }
            ?.let { return PolicyFinding(DisclosureLevel.L2_CATEGORICAL, it) }
        sentences.firstOrNull { generic.containsMatchIn(it) && sharing.containsMatchIn(it) }
            ?.let { return PolicyFinding(DisclosureLevel.L1_GENERIC, it) }
        return PolicyFinding(DisclosureLevel.L0_SILENT, null)
    }

    internal fun splitSentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“(])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
