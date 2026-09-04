package com.moveinsync.agentic.api;

import com.moveinsync.agentic.attribution.AttributionService;
import com.moveinsync.agentic.attribution.OnTimeGapAttribution;
import com.moveinsync.agentic.attribution.VendorGapContribution;
import com.moveinsync.agentic.narration.NarrationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
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
        String facts = buildFactsSummary(attribution);
        NarrationService.NarrationOutcome narration = narrationService.narrate(facts);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attribution", attribution);
        body.put("factsSummary", facts);
        body.put("narrative", narration.text());
        body.put("narrationProvider", narration.provider());
        body.put("usedFallback", narration.usedFallback());
        return body;
    }

    /**
     * Deterministically renders the attribution result into plain text -
     * this, not raw trip rows, is all the LLM ever sees. Every number in
     * the eventual narrative traces back to a line here.
     */
    private String buildFactsSummary(OnTimeGapAttribution a) {
        StringBuilder sb = new StringBuilder();
        int windowDays = (int) ChronoUnit.DAYS.between(a.periodStart(), a.periodEnd());

        sb.append(String.format(Locale.ROOT,
                "Metric: On-Time Arrival Rate%n" +
                "Period: %s to %s (%d days)%n" +
                "Fleet value: %.1f%% (SLA: %.0f%%, %s)%n",
                a.periodStart(), a.periodEnd(), windowDays,
                a.fleetOtaValue(), a.slaThreshold(),
                a.fleetSlaBreached() ? "SLA BREACHED" : "within SLA"));

        if (a.fleetOtaTrendAbsolute() != null) {
            sb.append(String.format(Locale.ROOT,
                    "Fleet trend vs prior period: %+.1f points%n", a.fleetOtaTrendAbsolute()));
        }

        sb.append("Vendor breakdown (sorted by share of fleet-wide late trips):\n");
        for (VendorGapContribution v : a.topContributors()) {
            String trendPart = v.otaTrendAbsolute() != null
                    ? String.format(Locale.ROOT, " (%+.1f pts vs prior period)", v.otaTrendAbsolute())
                    : "";
            String reasonPart = v.dominantReasonCode() != null
                    ? String.format(Locale.ROOT, ", dominant delay reason: %s (%.0f%% of its coded delays)",
                        v.dominantReasonCode(), v.reasonCodeBreakdown().get(0).sharePercent())
                    : "";
            sb.append(String.format(Locale.ROOT,
                    "- Vendor %s: OTA %.1f%%%s, %s, %.0f%% of all late trips fleet-wide (%d late trips)%s%n",
                    v.vendorId(), v.otaValue(), trendPart,
                    v.slaBreached() ? "SLA breached" : "within SLA",
                    v.gapContributionPercent(), v.lateTripCount(), reasonPart));
        }
        return sb.toString();
    }
}
