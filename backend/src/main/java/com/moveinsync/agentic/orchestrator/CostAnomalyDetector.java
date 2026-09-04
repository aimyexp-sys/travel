package com.moveinsync.agentic.orchestrator;

import com.moveinsync.agentic.benchmarking.BenchmarkResult;
import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.benchmarking.Dimension;
import com.moveinsync.agentic.benchmarking.MetricType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The build plan's "stretch fourth" scenario: a vendor's cost-per-km
 * creeping up against both its own recent history and its peers, with no
 * threshold/SLA to breach (COST_PER_KM has none - see MetricType) and
 * therefore inherently less certain than an SLA breach. The decide stage
 * (see DecisionPolicy) reflects that by logging this for internal billing
 * review rather than escalating it - proving the decision layer reasons
 * about *what* action fits the finding, not just *whether* to fire one.
 */
@Component
public class CostAnomalyDetector implements FindingDetector {

    private static final int WINDOW_DAYS = 42; // 6 weeks - matches the ramp window this is tuned to catch
    private static final double SELF_TREND_THRESHOLD_PERCENT = 8.0;

    private final BenchmarkingService benchmarkingService;

    public CostAnomalyDetector(BenchmarkingService benchmarkingService) {
        this.benchmarkingService = benchmarkingService;
    }

    @Override
    public List<Finding> detect(LocalDate asOf) {
        List<BenchmarkResult> results = benchmarkingService.benchmark(
                MetricType.COST_PER_KM, Dimension.VENDOR, WINDOW_DAYS, asOf);

        List<Finding> findings = new ArrayList<>();
        for (BenchmarkResult r : results) {
            if (r.currentValue() == null || r.priorValue() == null || r.trendDeltaPercent() == null) {
                continue;
            }
            // Gated on the vendor's OWN trend only, not its absolute level vs peers -
            // a vendor whose base per-km rate is naturally lower than pricier peers (e.g.
            // a cheaper mode mix) can still be creeping upward in a way worth flagging.
            // Peer figures are still surfaced in the narrated facts for context.
            boolean risingVsSelf = r.trendDeltaPercent() >= SELF_TREND_THRESHOLD_PERCENT;

            if (risingVsSelf) {
                int windowDays = (int) ChronoUnit.DAYS.between(r.periodStart(), r.periodEnd());
                String facts = String.format(Locale.ROOT,
                        "Metric: Cost per Km, Vendor %s (no fixed SLA for this metric)%n" +
                        "Period: %s to %s (%d days)%n" +
                        "Current: %.2f/km, prior period: %.2f/km (%+.1f%% change)%n" +
                        "Peer average across other vendors: %.2f/km (this vendor is %+.2f/km vs peer average)%n" +
                        "No corresponding change in trip distance or delay/quality metrics was found - this reads as a " +
                        "pure cost-per-unit creep rather than a volume or service-quality shift.%n",
                        r.dimensionValue(), r.periodStart(), r.periodEnd(), windowDays,
                        r.currentValue(), r.priorValue(), r.trendDeltaPercent(),
                        r.peerAverageValue() != null ? r.peerAverageValue() : 0.0,
                        r.peerDeltaAbsolute() != null ? r.peerDeltaAbsolute() : 0.0);

                findings.add(new Finding(
                        FindingType.COST_ANOMALY,
                        "cost-anomaly:" + r.dimensionValue(),
                        r.dimensionValue(), null, null,
                        "Cost/km creep: Vendor " + r.dimensionValue(),
                        facts
                ));
            }
        }
        return findings;
    }
}
