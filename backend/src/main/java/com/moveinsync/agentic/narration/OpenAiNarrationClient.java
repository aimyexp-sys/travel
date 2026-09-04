package com.moveinsync.agentic.narration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls OpenAI's chat completions endpoint. Active when llm.provider=openai
 * - the fallback path if SarvamAI credits run out or the key is unavailable
 * (see application.yml: LLM_PROVIDER env var). Same NarrationClient
 * contract as SarvamAiNarrationClient, so switching is a one-line change.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "openai")
public class OpenAiNarrationClient implements NarrationClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public OpenAiNarrationClient(
            @Value("${llm.openai.base-url:https://api.openai.com/v1/chat/completions}") String baseUrl,
            @Value("${llm.openai.api-key:}") String apiKey,
            @Value("${llm.openai.model:gpt-4o-mini}") String model
    ) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public String narrate(String factsSummary) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
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
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return NarrationPrompts.extractContent(response);
    }
}
