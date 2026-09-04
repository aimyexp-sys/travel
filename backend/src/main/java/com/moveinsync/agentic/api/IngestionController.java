package com.moveinsync.agentic.api;

import com.moveinsync.agentic.ingestion.IngestionRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin/dev convenience endpoints for Phase 2's ingestion layer. Not
 * authenticated - fine per the brief ("not expected: production-grade
 * authentication or security"), not fine for a real deployment.
 */
@RestController
@RequestMapping("/api/admin")
public class IngestionController {

    private final IngestionRunner ingestionRunner;
    private final JdbcTemplate jdbcTemplate;

    public IngestionController(IngestionRunner ingestionRunner, JdbcTemplate jdbcTemplate) {
        this.ingestionRunner = ingestionRunner;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Reloads the canonical schema from the configured data source.
     * force=true (the only supported mode here) truncates every canonical
     * table first - safe for a demo/dev dataset, not something you'd expose
     * unauthenticated in production.
     */
    @PostMapping("/ingest")
    public Object ingest(@RequestParam(defaultValue = "false") boolean force) {
        if (!force) {
            return Map.of("message", "Pass ?force=true to truncate and reload the canonical schema.");
        }
        return ingestionRunner.forceReload();
    }

    /** Row counts per canonical table, plus the most recent ingestion run and any data-quality findings. */
    @GetMapping("/ingestion-status")
    public Map<String, Object> status() {
        List<String> tables = List.of(
                "shifts", "vendors", "drivers", "routes", "employees", "trips",
                "trip_employees", "gps_traces", "delay_records", "safety_incidents",
                "feedback", "cost_records"
        );
        Map<String, Object> rowCounts = new java.util.LinkedHashMap<>();
        for (String table : tables) {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            rowCounts.put(table, count);
        }

        List<Map<String, Object>> lastRun = jdbcTemplate.queryForList(
                "SELECT id, source, started_at, finished_at, status, rows_loaded " +
                "FROM ingestion_runs ORDER BY id DESC LIMIT 1");

        List<Map<String, Object>> dataQualityFindings = jdbcTemplate.queryForList(
                "SELECT source_table, issue_type, issue_count, detail FROM data_quality_issues " +
                "WHERE ingestion_run_id = (SELECT id FROM ingestion_runs ORDER BY id DESC LIMIT 1) " +
                "ORDER BY issue_count DESC");

        return Map.of(
                "rowCounts", rowCounts,
                "lastRun", lastRun.isEmpty() ? null : lastRun.get(0),
                "dataQualityFindings", dataQualityFindings
        );
    }
}
