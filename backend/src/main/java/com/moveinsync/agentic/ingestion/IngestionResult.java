package com.moveinsync.agentic.ingestion;

import java.util.List;
import java.util.Map;

/**
 * Summary of one adapter run: how many rows landed in each table, and what
 * data-quality issues were detected (and flagged, not silently dropped) in
 * the process. Also returned by the admin ingestion endpoint so the
 * "handles messy data gracefully" behaviour is something you can actually
 * point at in the demo, not just a claim.
 */
public record IngestionResult(
        String source,
        Map<String, Integer> tableRowCounts,
        List<DataQualityFinding> dataQualityFindings,
        long durationMillis
) {
    public record DataQualityFinding(String sourceTable, String issueType, int issueCount, String detail) {}
}
