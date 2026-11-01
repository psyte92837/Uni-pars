package com.unipars.ai

/**
 * Approximate list prices, USD per 1M tokens (input, output). Used only for the user's estimate
 * and monthly limit; the provider's bill is authoritative. Unknown models return null.
 */
object Pricing {
    private val prices = mapOf(
        "gemini-3.5-flash-lite" to (0.30 to 2.50),
        "claude-opus-5-5" to (4.00 to 20.00),
        "claude-sonnet-5-5" to (2.00 to 10.00),
        "claude-haiku-5-5" to (0.10 to 0.50),
        "claude-fable-5-1" to (10.00 to 50.00),
    )

    fun estimateUsd(model: String, usage: AiUsage): Double? {
        val (inPrice, outPrice) = prices[model] ?: return null
        return usage.inputTokens / 1e6 * inPrice + usage.outputTokens / 1e6 * outPrice
    }
}
