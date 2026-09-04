package com.moveinsync.agentic.ingestion;

import com.moveinsync.agentic.ingestion.IngestionResult.DataQualityFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Loads the configured DataSourceAdapter into the canonical schema on
 * startup - but only if the schema looks empty, so restarting the backend
 * during a demo doesn't reload (and re-duplicate) data every time. Use the
 * admin endpoint (IngestionController) for an explicit forced reload.
 */
@Component
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final DataSourceAdapter adapter;
    private final JdbcTemplate jdbcTemplate;
    private final boolean autoRunEnabled;

    public IngestionRunner(
            DataSourceAdapter adapter,
            JdbcTemplate jdbcTemplate,
            @Value("${app.ingestion.auto-run:true}") boolean autoRunEnabled
    ) {
        this.adapter = adapter;
        this.jdbcTemplate = jdbcTemplate;
        this.autoRunEnabled = autoRunEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoRunEnabled) {
            log.info("Ingestion auto-run disabled (app.ingestion.auto-run=false); skipping.");
            return;
        }
        Integer existingTrips = jdbcTemplate.queryForObject("SELECT count(*) FROM trips", Integer.class);
        if (existingTrips != null && existingTrips > 0) {
            log.info("trips table already has {} rows - skipping auto-ingestion. " +
                    "Use POST /api/admin/ingest?force=true to reload.", existingTrips);
            return;
        }
        runIngestion();
    }

    /** Truncates all canonical tables and reloads from the configured adapter. */
    public IngestionResult forceReload() {
        log.info("Force reload requested - truncating canonical tables.");
        jdbcTemplate.execute(
                "TRUNCATE TABLE trip_employees, gps_traces, delay_records, safety_incidents, " +
                "feedback, cost_records, trips, employees, drivers, routes, vendors, shifts, " +
                "data_quality_issues RESTART IDENTITY CASCADE");
        return runIngestion();
    }

    private IngestionResult runIngestion() {
        OffsetDateTime startedAt = OffsetDateTime.now();
        Long runId = jdbcTemplate.queryForObject(
                "INSERT INTO ingestion_runs (source, started_at, status) VALUES (?, ?, 'RUNNING') RETURNING id",
                Long.class, adapter.sourceName(), startedAt);

        try {
            IngestionResult result = adapter.load();
            int totalRows = result.tableRowCounts().values().stream().mapToInt(Integer::intValue).sum();

            jdbcTemplate.update(
                    "UPDATE ingestion_runs SET finished_at = ?, status = 'SUCCEEDED', rows_loaded = ?, notes = ? WHERE id = ?",
                    OffsetDateTime.now(), totalRows, result.tableRowCounts().toString(), runId);

            for (DataQualityFinding f : result.dataQualityFindings()) {
                jdbcTemplate.update(
                        "INSERT INTO data_quality_issues (ingestion_run_id, source_table, issue_type, issue_count, detail) " +
                        "VALUES (?, ?, ?, ?, ?)",
                        runId, f.sourceTable(), f.issueType(), f.issueCount(), f.detail());
            }

            log.info("Ingestion run {} succeeded: {} total rows, {} data quality findings",
                    runId, totalRows, result.dataQualityFindings().size());
            return result;

        } catch (RuntimeException e) {
            jdbcTemplate.update(
                    "UPDATE ingestion_runs SET finished_at = ?, status = 'FAILED', notes = ? WHERE id = ?",
                    OffsetDateTime.now(), String.valueOf(e.getMessage()), runId);
            log.error("Ingestion run {} failed", runId, e);
            throw e;
        }
    }
}
