package com.moveinsync.agentic.benchmarking;

import java.time.LocalDate;

/**
 * A single benchmarked metric reading - this is the mandatory
 * "contextualize every metric" contract from the brief in object form:
 * every field needed to say "OTA is 78%, down from 85% last month, against
 * a 90% SLA, and Vendor A is 12 points below its peers" is here as data,
 * not assembled ad hoc per caller. Trend = vs. the immediately prior period
 * of equal length. SLA = vs. the fixed threshold in MetricType. Peer = vs.
 * the average of the other values in the same dimension/period.
 */
public record BenchmarkResult(
        String metric,
        String metricDisplayName,
        boolean higherIsBetter,
        String dimensionType,      // NONE | VENDOR | ZONE
        String dimensionValue,     // e.g. "V1", "Marathahalli", or "ALL" for NONE

        LocalDate periodStart,
        LocalDate periodEnd,       // exclusive

        Double currentValue,
        Integer currentSampleSize,

        Double priorValue,         // null if no data in the prior period
        Integer priorSampleSize,
        Double trendDeltaAbsolute,
        Double trendDeltaPercent,

        Double slaThreshold,       // null if this metric has no SLA
        Double slaGapAbsolute,     // currentValue - slaThreshold
        Boolean slaBreached,

        Double peerAverageValue,   // null for dimension NONE
        Double peerDeltaAbsolute
) {}
