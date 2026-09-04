package com.moveinsync.agentic.narration;

/**
 * Turns a deterministically-built facts summary into short natural-language
 * text. Implementations must never be asked to compute anything - the facts
 * string already contains every number the response should use; the LLM's
 * job is phrasing, not arithmetic (see the architecture doc's reasoning
 * split: cheap deterministic computation, an LLM call only for narration).
 *
 * Exactly one implementation is active at a time, selected by the
 * llm.provider config property - see SarvamAiNarrationClient and
 * OpenAiNarrationClient. Switching providers (e.g. SarvamAI credits run
 * out mid-hackathon) is a one-line config/env-var change, not a redeploy.
 */
public interface NarrationClient {

    /** Short id for logging/response metadata, e.g. "sarvam" or "openai". */
    String providerName();

    /** @throws RuntimeException on any failure - callers should go through NarrationService, which falls back gracefully. */
    String narrate(String factsSummary);
}
