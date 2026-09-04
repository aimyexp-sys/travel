package com.moveinsync.agentic.narration;

import java.util.List;
import java.util.Map;

/** Shared system prompt and response parsing for the OpenAI-compatible chat completions shape both providers use. */
final class NarrationPrompts {

    private NarrationPrompts() {}

    static final String SYSTEM_PROMPT = """
        You write short, factual operational alerts for enterprise mobility
        managers (cab/shuttle transport operations). You will be given a
        block of pre-computed facts (metric values, trends, SLA thresholds,
        vendor contributions, delay reason codes). Write 1-3 plain sentences
        summarizing what matters and why, in the tone of a competent
        operations analyst. Use ONLY the numbers given - never invent,
        estimate, or round differently than what's provided. Do not add
        recommendations unless asked. No markdown, no bullet points.
        """;

    /** Parses the standard {choices: [{message: {content: "..."}}]} shape used by both providers. */
    @SuppressWarnings("unchecked")
    static String extractContent(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalStateException("Empty response from narration provider");
        }
        Object choicesObj = response.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("No choices in narration provider response: " + response);
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("Unexpected choice shape: " + first);
        }
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof Map<?, ?> message)) {
            throw new IllegalStateException("Unexpected message shape: " + messageObj);
        }
        Object content = message.get("content");
        if (content == null) {
            throw new IllegalStateException("No content in narration provider message: " + message);
        }
        return content.toString().trim();
    }
}
