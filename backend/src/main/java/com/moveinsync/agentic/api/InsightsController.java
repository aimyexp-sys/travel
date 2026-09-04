package com.moveinsync.agentic.api;

import com.moveinsync.agentic.attribution.AttributionFacts;
import com.moveinsync.agentic.attribution.AttributionService;
import com.moveinsync.agentic.attribution.OnTimeGapAttribution;
import com.moveinsync.agentic.narration.NarrationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ties Phase 3's benchmarking, Phase 4's deterministic attribution, and
 * Phase 4's narration together into one demo-ready endpoint - this is the
 * brief's own worked example ("OTA is 78%, down from 85%, SLA is 90%, two
 * vendors responsible for the gap") computed and narrated end to end, live,
 * on this dataset.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final AttributionService attributionService;
    private final NarrationService narrationService;

    public InsightsController(AttributionService attributionService, NarrationService narrationService) {
        this.attributionService = attributionService;
        this.narrationService = narrationService;
    }

    @GetMapping("/on-time-gap")
    public Map<String, Object> onTimeGap(
            @RequestParam(defaultValue = "28") int windowDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        OnTimeGapAttribution attribution = attributionService.attributeOnTimeGap(windowDays, asOf);
        String facts = AttributionFacts.format(attribution);
        NarrationService.NarrationOutcome narration = narrationService.narrate(facts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attribution", attribution);
        body.put("factsSummary", facts);
        body.put("narrative", narration.text());
        body.put("narrationProvider", narration.provider());
        body.put("usedFallback", narration.usedFallback());
        return body;
    }

}
