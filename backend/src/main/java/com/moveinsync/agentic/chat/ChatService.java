package com.moveinsync.agentic.chat;

import com.moveinsync.agentic.attribution.AttributionFacts;
import com.moveinsync.agentic.attribution.AttributionService;
import com.moveinsync.agentic.attribution.OnTimeGapAttribution;
import com.moveinsync.agentic.benchmarking.BenchmarkResult;
import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.benchmarking.Dimension;
import com.moveinsync.agentic.benchmarking.MetricType;
import com.moveinsync.agentic.narration.NarrationService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7 (optional good-to-have): a conversational drill-down layered over
 * the SAME attribution/benchmarking/narration services the rest of the app
 * uses - "why did OTA drop this week" gets the identical reasoning as the
 * dashboard, just triggered by a question instead of a fixed endpoint. This
 * is deliberately NOT a second reasoning pipeline: intent detection here is
 * plain keyword matching (kept in Java, not the LLM) that decides WHICH
 * existing deterministic service to call, and the facts it produces are
 * still the only thing the narration call ever sees - same discipline as
 * everywhere else in the app.
 */
@Service
public class ChatService {

    private static final int WINDOW_DAYS = 28;

    private final AttributionService attributionService;
    private final BenchmarkingService benchmarkingService;
    private final NarrationService narrationService;
    private final ChatLookupRepository lookup;

    public ChatService(AttributionService attributionService,
                        BenchmarkingService benchmarkingService,
                        NarrationService narrationService,
                        ChatLookupRepository lookup) {
        this.attributionService = attributionService;
        this.benchmarkingService = benchmarkingService;
        this.narrationService = narrationService;
        this.lookup = lookup;
    }

    public Map<String, Object> answer(String message) {
        String question = message == null ? "" : message.trim();
        if (question.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("question", question);
            body.put("matchedSubject", "none");
            body.put("answer", "Ask about on-time performance, cost, safety, or feedback - "
                    + "e.g. \"why did on-time arrival drop this week\" or \"how is Vendor A doing on cost\".");
            body.put("usedFallback", true);
            return body;
        }

        String lower = question.toLowerCase(Locale.ROOT);
        LocalDate asOf = benchmarkingService.latestDataDate();
        String vendorId = lookup.matchVendor(lower);
        String zone = lookup.matchZone(lower);

        String subject;
        String facts;
        if (containsAny(lower, "cost", "price", "expensive", "spend", "budget", "per km")) {
            subject = "cost";
            facts = metricFacts(MetricType.COST_PER_KM, vendorId, asOf, false);
        } else if (containsAny(lower, "safety", "incident", "accident", "harsh brak", "near miss", "collision")) {
            subject = "safety";
            facts = metricFacts(MetricType.SAFETY_INCIDENT_RATE, vendorId, asOf, false);
        } else if (containsAny(lower, "feedback", "rating", "satisfaction", "complaint", "rider experience")) {
            subject = "feedback";
            facts = metricFacts(MetricType.FEEDBACK_SCORE, vendorId, asOf, true);
        } else if (zone != null) {
            subject = "zone";
            facts = zoneFacts(zone, asOf);
        } else if (containsAny(lower, "on-time", "on time", "ota", "late", "delay", "punctual", "arrival") || vendorId != null) {
            subject = "on-time performance";
            facts = onTimeFacts(vendorId, asOf);
        } else {
            subject = "fleet snapshot";
            facts = generalFacts(asOf);
        }

        String factsForNarration = "User question: " + question + "\n\n" + facts;
        NarrationService.NarrationOutcome outcome = narrationService.narrate(factsForNarration);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", question);
        body.put("matchedSubject", subject);
        body.put("vendorId", vendorId);
        body.put("zone", zone);
        body.put("factsSummary", facts);
        body.put("answer", outcome.text());
        body.put("narrationProvider", outcome.provider());
        body.put("usedFallback", outcome.usedFallback());
        return body;
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private String onTimeFacts(String vendorId, LocalDate asOf) {
        OnTimeGapAttribution attribution = attributionService.attributeOnTimeGap(WINDOW_DAYS, asOf);
        String facts = AttributionFacts.format(attribution);
        if (vendorId != null) {
            facts = "(Question specifically named Vendor " + vendorId + " - see its line in the breakdown below.)\n" + facts;
        }
        return facts;
    }

    /** Fleet value + full vendor ranking for one metric, optionally narrowed to one vendor. */
    private String metricFacts(MetricType metric, String vendorId, LocalDate asOf, boolean higherIsBetter) {
        BenchmarkResult fleet = benchmarkingService.benchmark(metric, Dimension.NONE, WINDOW_DAYS, asOf).get(0);
        List<BenchmarkResult> perVendor = benchmarkingService.benchmark(metric, Dimension.VENDOR, WINDOW_DAYS, asOf);

        Comparator<BenchmarkResult> worstFirst = higherIsBetter
                ? Comparator.comparing(BenchmarkResult::currentValue, Comparator.nullsLast(Comparator.naturalOrder()))
                : Comparator.comparing(BenchmarkResult::currentValue, Comparator.nullsLast(Comparator.reverseOrder()));
        perVendor = perVendor.stream().filter(r -> r.currentValue() != null).sorted(worstFirst).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Metric: %s%nPeriod: %s to %s (%d days)%n", metric.displayName(), fleet.periodStart(), fleet.periodEnd(), WINDOW_DAYS));
        appendResultLine(sb, "Fleet-wide", fleet);

        if (vendorId != null) {
            perVendor.stream().filter(r -> vendorId.equals(r.dimensionValue())).findFirst()
                    .ifPresent(r -> appendResultLine(sb, "Vendor " + vendorId + " (asked about)", r));
        }

        sb.append("Vendor ranking (worst first):\n");
        for (BenchmarkResult r : perVendor) {
            appendResultLine(sb, "  Vendor " + r.dimensionValue(), r);
        }
        return sb.toString();
    }

    private String zoneFacts(String zone, LocalDate asOf) {
        List<BenchmarkResult> perZone = benchmarkingService.benchmark(
                MetricType.AVERAGE_DELAY_MINUTES, Dimension.ZONE, WINDOW_DAYS, asOf);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Metric: Average Delay, by pickup zone%nPeriod: last %d days%n", WINDOW_DAYS));
        for (BenchmarkResult r : perZone) {
            if (r.currentValue() == null) continue;
            String marker = zone.equals(r.dimensionValue()) ? " <-- zone asked about" : "";
            appendResultLine(sb, "Zone " + r.dimensionValue() + marker, r);
        }
        return sb.toString();
    }

    private String generalFacts(LocalDate asOf) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Fleet-wide KPI snapshot, trailing %d days:%n", WINDOW_DAYS));
        for (MetricType metric : MetricType.values()) {
            BenchmarkResult r = benchmarkingService.benchmark(metric, Dimension.NONE, WINDOW_DAYS, asOf).get(0);
            appendResultLine(sb, metric.displayName(), r);
        }
        return sb.toString();
    }

    private void appendResultLine(StringBuilder sb, String label, BenchmarkResult r) {
        if (r.currentValue() == null) {
            sb.append(String.format(Locale.ROOT, "- %s: no data in this window%n", label));
            return;
        }
        String slaPart = r.slaThreshold() != null
                ? String.format(Locale.ROOT, ", SLA %.1f (%s)", r.slaThreshold(),
                    Boolean.TRUE.equals(r.slaBreached()) ? "BREACHED" : "met")
                : "";
        String trendPart = r.trendDeltaAbsolute() != null
                ? String.format(Locale.ROOT, ", trend %+.2f vs prior period", r.trendDeltaAbsolute())
                : "";
        String peerPart = r.peerAverageValue() != null
                ? String.format(Locale.ROOT, ", peer avg %.2f", r.peerAverageValue())
                : "";
        sb.append(String.format(Locale.ROOT, "- %s: %.2f%s%s%s%n", label, r.currentValue(), slaPart, trendPart, peerPart));
    }
}
