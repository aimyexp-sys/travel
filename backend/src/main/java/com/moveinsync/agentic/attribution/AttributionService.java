package com.moveinsync.agentic.attribution;

import com.moveinsync.agentic.benchmarking.BenchmarkResult;
import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.benchmarking.Dimension;
import com.moveinsync.agentic.benchmarking.MetricType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic root-cause decomposition - deliberately kept LLM-free (see
 * the architecture doc's reasoning-split rationale). Given a benchmarked
 * metric, finds which vendor(s) are the largest contributors to a
 * fleet-wide shortfall, and what's driving each one's delays. Only the
 * resulting structured facts (built by InsightsController) are ever handed
 * to NarrationService - the LLM never touches a raw trip row.
 */
@Service
public class AttributionService {

    private final BenchmarkingService benchmarkingService;
    private final JdbcTemplate jdbc;

    public AttributionService(BenchmarkingService benchmarkingService, JdbcTemplate jdbc) {
        this.benchmarkingService = benchmarkingService;
        this.jdbc = jdbc;
    }

    /**
     * The brief's own worked example, computed for real: fleet OTA vs
     * SLA/trend, decomposed to which vendors are responsible for the gap
     * and why (delay reason code breakdown per vendor).
     */
    public OnTimeGapAttribution attributeOnTimeGap(int windowDays, LocalDate asOf) {
        LocalDate effectiveAsOf = (asOf != null) ? asOf : benchmarkingService.latestDataDate();

        BenchmarkResult fleet = benchmarkingService
                .benchmark(MetricType.ON_TIME_ARRIVAL_RATE, Dimension.NONE, windowDays, effectiveAsOf)
                .get(0);
        List<BenchmarkResult> perVendor = benchmarkingService
                .benchmark(MetricType.ON_TIME_ARRIVAL_RATE, Dimension.VENDOR, windowDays, effectiveAsOf);

        LocalDateTime currentEnd = effectiveAsOf.plusDays(1).atStartOfDay();
        LocalDateTime currentStart = currentEnd.minusDays(windowDays);

        Map<String, Integer> lateCountsByVendor = lateTripCountsByVendor(currentStart, currentEnd);
        int fleetLateCount = lateCountsByVendor.values().stream().mapToInt(Integer::intValue).sum();

        List<VendorGapContribution> contributors = new ArrayList<>();
        for (BenchmarkResult v : perVendor) {
            if (v.currentValue() == null) continue; // no trips for this vendor in the window
            int lateCount = lateCountsByVendor.getOrDefault(v.dimensionValue(), 0);
            double contributionPct = fleetLateCount > 0 ? (lateCount * 100.0 / fleetLateCount) : 0.0;
            List<ReasonCodeBreakdown> reasons = reasonCodeBreakdown(v.dimensionValue(), currentStart, currentEnd);
            String dominant = reasons.isEmpty() ? null : reasons.get(0).reasonCode();

            contributors.add(new VendorGapContribution(
                    v.dimensionValue(), v.currentValue(), v.trendDeltaAbsolute(),
                    Boolean.TRUE.equals(v.slaBreached()), lateCount, contributionPct, reasons, dominant
            ));
        }

        // Largest contributors to the shortfall first - this ordering is
        // literally what "two vendors are responsible for the gap" means.
        contributors.sort(Comparator.comparingDouble(VendorGapContribution::gapContributionPercent).reversed());

        return new OnTimeGapAttribution(
                currentStart.toLocalDate(), currentEnd.toLocalDate(),
                fleet.currentValue() != null ? fleet.currentValue() : 0.0,
                fleet.trendDeltaAbsolute(),
                fleet.slaThreshold() != null ? fleet.slaThreshold() : 0.0,
                Boolean.TRUE.equals(fleet.slaBreached()),
                contributors
        );
    }

    private Map<String, Integer> lateTripCountsByVendor(LocalDateTime start, LocalDateTime end) {
        String sql = """
            WITH dedup AS (
                SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, d.delay_minutes
                FROM trips t
                JOIN delay_records d ON d.trip_id = t.trip_id
                WHERE t.status = 'completed'
                  AND t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                ORDER BY t.trip_id, t.id
            )
            SELECT vendor_id, count(*) AS late_count
            FROM dedup
            WHERE delay_minutes > 10
            GROUP BY vendor_id
            """;
        return jdbc.query(sql, ps -> {
            ps.setTimestamp(1, Timestamp.valueOf(start));
            ps.setTimestamp(2, Timestamp.valueOf(end));
        }, rs -> {
            Map<String, Integer> result = new LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getString("vendor_id"), rs.getInt("late_count"));
            }
            return result;
        });
    }

    private List<ReasonCodeBreakdown> reasonCodeBreakdown(String vendorId, LocalDateTime start, LocalDateTime end) {
        String sql = """
            WITH dedup AS (
                SELECT DISTINCT ON (t.trip_id) t.trip_id, d.delay_reason_code
                FROM trips t
                JOIN delay_records d ON d.trip_id = t.trip_id
                WHERE t.vendor_id = ?
                  AND t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                  AND d.delay_reason_code IS NOT NULL
                ORDER BY t.trip_id, t.id
            )
            SELECT delay_reason_code, count(*) AS n
            FROM dedup
            GROUP BY delay_reason_code
            ORDER BY n DESC
            """;
        List<Object[]> rows = jdbc.query(sql, ps -> {
            ps.setString(1, vendorId);
            ps.setTimestamp(2, Timestamp.valueOf(start));
            ps.setTimestamp(3, Timestamp.valueOf(end));
        }, rs -> {
            List<Object[]> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Object[]{rs.getString("delay_reason_code"), rs.getInt("n")});
            }
            return result;
        });

        int total = rows.stream().mapToInt(r -> (Integer) r[1]).sum();
        List<ReasonCodeBreakdown> result = new ArrayList<>();
        for (Object[] row : rows) {
            String code = (String) row[0];
            int count = (Integer) row[1];
            double share = total > 0 ? count * 100.0 / total : 0.0;
            result.add(new ReasonCodeBreakdown(code, count, share));
        }
        return result;
    }
}
