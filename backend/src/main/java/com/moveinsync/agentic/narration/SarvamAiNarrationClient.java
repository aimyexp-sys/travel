package com.moveinsync.agentic.narration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls SarvamAI's chat completions endpoint. Active when llm.provider=sarvam
 * (the default - see application.yml).
 *
 * Base URL, the "api-subscription-key" auth header, and the OpenAI-style
 * {choices:[{message:{content}}]} response shape are all confirmed correct
 * against a live key - a bad model name (SarvamAI deprecates model ids
 * periodically) surfaced as a clean structured 400, not a connection
 * failure. If SarvamAI deprecates "sarvam-105b-conversations" too, the fix
 * is the same one-line @Value default below (or SARVAM_MODEL in .env) - no
 * other code changes needed. If a call fails for ANY reason, NarrationService
 * catches it and falls back to a deterministic templated sentence rather
 * than breaking the pipeline.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "sarvam", matchIfMissing = true)
public class SarvamAiNarrationClient implements NarrationClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public SarvamAiNarrationClient(
            @Value("${llm.sarvam.base-url:https://api.sarvam.ai/v1/chat/completions}") String baseUrl,
            @Value("${llm.sarvam.api-key:}") String apiKey,
            @Value("${llm.sarvam.model:sarvam-105b-conversations}") String model
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String providerName() {
        return "sarvam";
    }

    @Override
    public String narrate(String factsSummary) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("SARVAM_API_KEY not set");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", NarrationPrompts.SYSTEM_PROMPT),
                        Map.of("role", "user", "content", factsSummary)
                ),
                "temperature", 0.2,
                "max_tokens", 200
        );

        Map<?, ?> response = restClient.post()
                .header("api-subscription-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return NarrationPrompts.extractContent(response);
    }
}
