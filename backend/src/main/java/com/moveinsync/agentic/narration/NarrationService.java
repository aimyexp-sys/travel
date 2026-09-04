package com.moveinsync.agentic.narration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The narration entry point the rest of the app actually depends on -
 * never inject NarrationClient directly. Wraps whichever provider is active
 * (SarvamAI or OpenAI, per llm.provider) and falls back to a deterministic
 * templated rendering of the same facts on ANY failure: bad key, network
 * outage, unexpected response shape. This is what keeps a narration outage
 * (including venue wifi dropping mid-demo - see the architecture doc's risk
 * table) from ever breaking the pipeline; the reasoning underneath still
 * ran correctly, only the prose quality degrades.
 */
@Service
public class NarrationService {

    private static final Logger log = LoggerFactory.getLogger(NarrationService.class);

    private final NarrationClient narrationClient;

    public NarrationService(NarrationClient narrationClient) {
        this.narrationClient = narrationClient;
    }

    public record NarrationOutcome(String text, String provider, boolean usedFallback) {}

    public NarrationOutcome narrate(String factsSummary) {
        try {
            String text = narrationClient.narrate(factsSummary);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Empty narration text returned");
            }
            return new NarrationOutcome(text, narrationClient.providerName(), false);
        } catch (Exception e) {
            log.warn("Narration via {} failed ({}); falling back to templated summary.",
                    narrationClient.providerName(), e.getMessage());
            return new NarrationOutcome(templatedFallback(factsSummary), "template-fallback", true);
        }
    }

    private String templatedFallback(String factsSummary) {
        return "Automated narration unavailable - here are the underlying findings:\n\n" + factsSummary;
    }
}
