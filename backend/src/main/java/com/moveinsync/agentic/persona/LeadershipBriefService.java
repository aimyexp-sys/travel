package com.moveinsync.agentic.persona;

import com.moveinsync.agentic.benchmarking.BenchmarkResult;
import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.benchmarking.Dimension;
import com.moveinsync.agentic.benchmarking.MetricType;
import com.moveinsync.agentic.narration.NarrationService;
import com.moveinsync.agentic.orchestrator.AgentAuditRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The transport & facilities head's output surface (Phase 6 picks this
 * persona as the strongest default, per the build plan's bonus-criteria
 * reasoning). This is the build plan's Scenario 3 made real: a leadership-
 * ready brief rendered from a FIXED template (not free-form) - formatted,
 * no jargon, no raw numbers without context, forward-ready as-is.
 *
 * Deliberately reuses work already done rather than re-deriving it: the
 * "top problem areas" section is literally the same narrated sentences
 * Phase 5's agent already produced and stored in agent_actions (no new LLM
 * calls, no re-explaining something already explained) - only the opening
 * executive-summary paragraph is a fresh narration call, over a small
 * facts block covering the fleet-wide KPI snapshot. This split is the same
 * cost/latency discipline as the rest of the app: narration only ever
 * touches pre-computed facts, never raw trip rows, and never repeats work.
 */
@Service
public class LeadershipBriefService {

    private static final int METRIC_WINDOW_DAYS = 28;
    private static final MetricType[] KEY_METRICS = {
            MetricType.ON_TIME_ARRIVAL_RATE,
            MetricType.COST_PER_KM,
            MetricType.SAFETY_INCIDENT_RATE,
            MetricType.FEEDBACK_SCORE
    };
    private static final int MAX_PROBLEM_AREAS = 3;
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            "PENDING_APPROVAL", 0,
            "AUTO_FIRED", 1,
            "LOGGED_INTERNAL", 2,
            "APPROVED", 3
    );

    private final BenchmarkingService benchmarkingService;
    private final AgentAuditRepository auditRepository;
    private final NarrationService narrationService;

    public LeadershipBriefService(BenchmarkingService benchmarkingService,
                                   AgentAuditRepository auditRepository,
                                   NarrationService narrationService) {
        this.benchmarkingService = benchmarkingService;
        this.auditRepository = auditRepository;
        this.narrationService = narrationService;
    }

    public Map<String, Object> buildBrief() {
        LocalDate asOf = benchmarkingService.latestDataDate();

        List<BenchmarkResult> fleetMetrics = new ArrayList<>();
        for (MetricType metric : KEY_METRICS) {
            fleetMetrics.add(benchmarkingService.benchmark(metric, Dimension.NONE, METRIC_WINDOW_DAYS, asOf).get(0));
        }

        String metricsFacts = buildMetricsFacts(fleetMetrics, asOf);
        NarrationService.NarrationOutcome executiveSummary = narrationService.narrate(metricsFacts);

        List<Map<String, Object>> topProblemAreas = auditRepository.listActions(null, 50).stream()
                .filter(a -> !"DISMISSED".equals(a.get("status")))
                .sorted((a, b) -> rank(a.get("status")).compareTo(rank(b.get("status"))))
                .limit(MAX_PROBLEM_AREAS)
                .map(this::toProblemAreaView)
                .toList();

        List<String> wins = buildWins(fleetMetrics);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("periodEnd", asOf.toString());
        body.put("windowDays", METRIC_WINDOW_DAYS);
        body.put("executiveSummary", executiveSummary.text());
        body.put("executiveSummaryProvider", executiveSummary.provider());
        body.put("fleetMetrics", fleetMetrics);
        body.put("topProblemAreas", topProblemAreas);
        body.put("wins", wins);
        return body;
    }

    private Integer rank(Object status) {
        Integer r = STATUS_RANK.get(String.valueOf(status));
        return r != null ? r : 99;
    }

    private Map<String, Object> toProblemAreaView(Map<String, Object> action) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("title", action.get("title"));
        view.put("narrative", action.get("narrative"));
        view.put("status", action.get("status"));
        return view;
    }

    private String buildMetricsFacts(List<BenchmarkResult> metrics, LocalDate asOf) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Fleet-wide KPI snapshot as of %s, trailing %d days:%n", asOf, METRIC_WINDOW_DAYS));
        for (BenchmarkResult r : metrics) {
            if (r.currentValue() == null) continue;
            String slaPart = r.slaThreshold() != null
                    ? String.format(Locale.ROOT, ", SLA %.1f (%s)", r.slaThreshold(),
                        Boolean.TRUE.equals(r.slaBreached()) ? "BREACHED" : "met")
                    : "";
            String trendPart = r.trendDeltaAbsolute() != null
                    ? String.format(Locale.ROOT, ", trend %+.2f vs prior period", r.trendDeltaAbsolute())
                    : "";
            sb.append(String.format(Locale.ROOT, "- %s: %.2f%s%s%n",
                    r.metricDisplayName(), r.currentValue(), slaPart, trendPart));
        }
        return sb.toString();
    }

    /** A metric counts as a "win" when it's within SLA and trending in the favorable direction. */
    private List<String> buildWins(List<BenchmarkResult> metrics) {
        List<String> wins = new ArrayList<>();
        for (BenchmarkResult r : metrics) {
            if (r.currentValue() == null || r.trendDeltaAbsolute() == null) continue;
            boolean withinSla = r.slaThreshold() == null || !Boolean.TRUE.equals(r.slaBreached());
            boolean favorableTrend = r.higherIsBetter()
                    ? r.trendDeltaAbsolute() > 0
                    : r.trendDeltaAbsolute() < 0;
            if (withinSla && favorableTrend) {
                wins.add(String.format(Locale.ROOT, "%s improved to %.2f (%+.2f vs prior period), within SLA.",
                        r.metricDisplayName(), r.currentValue(), r.trendDeltaAbsolute()));
            }
        }
        return wins;
    }
}
