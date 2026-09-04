package com.moveinsync.agentic.benchmarking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the six MetricTypes, sliced by Dimension, with trend/SLA/peer
 * context attached to every result - this is the mandatory
 * "contextualize metrics against a reference point" requirement from the
 * brief, built as a first-class, reusable service rather than assembled ad
 * hoc wherever a metric is displayed. Everything from Phase 4's attribution
 * layer through the agent's alerts (Phase 5) reads from this class.
 *
 * "Now" is anchored to the latest date actually present in the trips table
 * (latestDataDate()), not the wall clock - the dataset is a fixed historical
 * sample, so "last 7 days" has to mean the last 7 days of data, not of
 * real time.
 *
 * Duplicate trip rows (a deliberately planted data-quality issue - see
 * Phase 2's SyntheticSourceAdapter) are collapsed with a
 * "DISTINCT ON (trip_id) ... ORDER BY trip_id, id" dedup in every query
 * here, so they're handled gracefully rather than double-counted.
 */
@Service
public class BenchmarkingService {

    private final JdbcTemplate jdbc;

    public BenchmarkingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest scheduled_pickup_time date in the dataset - the anchor for "now". */
    public LocalDate latestDataDate() {
        LocalDateTime max = jdbc.queryForObject("SELECT max(scheduled_pickup_time) FROM trips", LocalDateTime.class);
        if (max == null) {
            throw new IllegalStateException("No trips loaded yet - run ingestion first (POST /api/admin/ingest?force=true).");
        }
        return max.toLocalDate();
    }

    /**
     * Benchmarks one metric over the {@code windowDays} ending on
     * {@code asOf} (defaults to latestDataDate()), for every value of
     * {@code dimension} (a single "ALL" row when dimension is NONE).
     */
    public List<BenchmarkResult> benchmark(MetricType metric, Dimension dimension, int windowDays, LocalDate asOf) {
        LocalDate effectiveAsOf = (asOf != null) ? asOf : latestDataDate();
        LocalDateTime currentEnd = effectiveAsOf.plusDays(1).atStartOfDay(); // exclusive, covers all of asOf's day
        LocalDateTime currentStart = currentEnd.minusDays(windowDays);
        LocalDateTime priorEnd = currentStart;
        LocalDateTime priorStart = priorEnd.minusDays(windowDays);

        Map<String, MetricSample> current = computeGrouped(metric, dimension, currentStart, currentEnd);
        Map<String, MetricSample> prior = computeGrouped(metric, dimension, priorStart, priorEnd);

        List<String> keys = switch (dimension) {
            case NONE -> List.of("ALL");
            case VENDOR -> jdbc.queryForList("SELECT vendor_id FROM vendors ORDER BY vendor_id", String.class);
            case ZONE -> jdbc.queryForList("SELECT DISTINCT origin_zone FROM routes ORDER BY origin_zone", String.class);
        };

        List<BenchmarkResult> results = new ArrayList<>();
        for (String key : keys) {
            MetricSample curr = current.getOrDefault(key, new MetricSample(null, 0));
            MetricSample prev = prior.get(key);
            Double peerAvg = (dimension != Dimension.NONE) ? peerAverage(current, key) : null;
            results.add(buildResult(metric, dimension, key,
                    currentStart.toLocalDate(), currentEnd.toLocalDate(), curr, prev, peerAvg));
        }
        return results;
    }

    private Double peerAverage(Map<String, MetricSample> current, String excludeKey) {
        List<Double> others = current.entrySet().stream()
                .filter(e -> !e.getKey().equals(excludeKey))
                .map(e -> e.getValue().value())
                .filter(Objects::nonNull)
                .toList();
        if (others.isEmpty()) return null;
        return others.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private BenchmarkResult buildResult(MetricType metric, Dimension dimension, String key,
                                         LocalDate periodStart, LocalDate periodEnd,
                                         MetricSample curr, MetricSample prev, Double peerAvg) {
        Double currentValue = curr.value();
        Double priorValue = (prev != null) ? prev.value() : null;

        Double trendDeltaAbs = (currentValue != null && priorValue != null) ? currentValue - priorValue : null;
        Double trendDeltaPct = (trendDeltaAbs != null && priorValue != 0.0)
                ? (trendDeltaAbs / Math.abs(priorValue)) * 100.0 : null;

        Double sla = metric.slaThreshold();
        Double slaGap = (currentValue != null && sla != null) ? currentValue - sla : null;
        Boolean slaBreached = null;
        if (currentValue != null && sla != null) {
            slaBreached = metric.higherIsBetter() ? currentValue < sla : currentValue > sla;
        }

        Double peerDelta = (currentValue != null && peerAvg != null) ? currentValue - peerAvg : null;

        return new BenchmarkResult(
                metric.key(), metric.displayName(), metric.higherIsBetter(),
                dimension.name(), key,
                periodStart, periodEnd,
                currentValue, curr.sampleSize(),
                priorValue, prev != null ? prev.sampleSize() : null,
                trendDeltaAbs, trendDeltaPct,
                sla, slaGap, slaBreached,
                peerAvg, peerDelta
        );
    }

    /** Runs the metric-specific aggregate query, grouped by the given dimension, over [start, end). */
    private Map<String, MetricSample> computeGrouped(MetricType metric, Dimension dimension,
                                                       LocalDateTime start, LocalDateTime end) {
        String dimSelect = switch (dimension) {
            case NONE -> "'ALL'";
            case VENDOR -> "vendor_id";
            case ZONE -> "origin_zone";
        };
        String sql = buildSql(metric, dimSelect);

        return jdbc.query(sql, ps -> {
            ps.setTimestamp(1, Timestamp.valueOf(start));
            ps.setTimestamp(2, Timestamp.valueOf(end));
        }, rs -> {
            Map<String, MetricSample> result = new LinkedHashMap<>();
            while (rs.next()) {
                String dim = rs.getString("dim");
                double value = rs.getDouble("value");
                boolean valueWasNull = rs.wasNull();
                int n = rs.getInt("n");
                result.put(dim, new MetricSample(valueWasNull ? null : value, n));
            }
            return result;
        });
    }

    /**
     * Every query dedupes trips via "DISTINCT ON (t.trip_id) ... ORDER BY
     * t.trip_id, t.id" before aggregating - see the class Javadoc.
     * GROUP BY dim (the output alias) is valid PostgreSQL and keeps each
     * branch identical apart from which column dimSelect names.
     */
    private String buildSql(MetricType metric, String dimSelect) {
        return switch (metric) {
            case ON_TIME_ARRIVAL_RATE -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone, d.delay_minutes
                    FROM trips t
                    JOIN delay_records d ON d.trip_id = t.trip_id
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.status = 'completed'
                      AND t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                )
                SELECT %s AS dim,
                       count(*) FILTER (WHERE delay_minutes <= 10) * 100.0 / NULLIF(count(*), 0) AS value,
                       count(*) AS n
                FROM dedup
                GROUP BY dim
                """.formatted(dimSelect);

            case AVERAGE_DELAY_MINUTES -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone, d.delay_minutes
                    FROM trips t
                    JOIN delay_records d ON d.trip_id = t.trip_id
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                )
                SELECT %s AS dim, avg(delay_minutes) AS value, count(*) AS n
                FROM dedup
                GROUP BY dim
                """.formatted(dimSelect);

            case COST_PER_KM -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone, c.total_cost, t.distance_km
                    FROM trips t
                    JOIN cost_records c ON c.trip_id = t.trip_id
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                )
                SELECT %s AS dim, sum(total_cost) / NULLIF(sum(distance_km), 0) AS value, count(*) AS n
                FROM dedup
                GROUP BY dim
                """.formatted(dimSelect);

            case COST_PER_TRIP -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone, c.total_cost
                    FROM trips t
                    JOIN cost_records c ON c.trip_id = t.trip_id
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                )
                SELECT %s AS dim, avg(total_cost) AS value, count(*) AS n
                FROM dedup
                GROUP BY dim
                """.formatted(dimSelect);

            case SAFETY_INCIDENT_RATE -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone
                    FROM trips t
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                ),
                incident_counts AS (
                    SELECT si.trip_id, count(*) AS incidents
                    FROM safety_incidents si
                    GROUP BY si.trip_id
                )
                SELECT %s AS dim,
                       coalesce(sum(ic.incidents), 0) * 100.0 / NULLIF(count(dedup.trip_id), 0) AS value,
                       count(dedup.trip_id) AS n
                FROM dedup
                LEFT JOIN incident_counts ic ON ic.trip_id = dedup.trip_id
                GROUP BY dim
                """.formatted(dimSelect);

            case FEEDBACK_SCORE -> """
                WITH dedup AS (
                    SELECT DISTINCT ON (t.trip_id) t.trip_id, t.vendor_id, r.origin_zone
                    FROM trips t
                    LEFT JOIN routes r ON r.route_id = t.route_id
                    WHERE t.scheduled_pickup_time >= ? AND t.scheduled_pickup_time < ?
                    ORDER BY t.trip_id, t.id
                )
                SELECT %s AS dim, avg(f.rating) AS value, count(f.rating) AS n
                FROM dedup
                JOIN feedback f ON f.trip_id = dedup.trip_id
                GROUP BY dim
                """.formatted(dimSelect);
        };
    }
}
