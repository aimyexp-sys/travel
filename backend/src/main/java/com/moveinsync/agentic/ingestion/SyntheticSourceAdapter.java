package com.moveinsync.agentic.ingestion;

import com.moveinsync.agentic.ingestion.CsvTableLoader.ColumnSpec;
import com.moveinsync.agentic.ingestion.IngestionResult.DataQualityFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.moveinsync.agentic.ingestion.CsvTableLoader.ColumnType.*;

/**
 * Loads the Phase 0 synthetic dataset (data-generator/generate_data.py's
 * output) into the canonical schema. The CSVs already use the canonical
 * table/column shape (see /data/SCHEMA.md), so this adapter is close to a
 * no-op mapper - table specs below exist mainly to (a) declare column
 * types for CsvTableLoader and (b) rename the one awkward column
 * (gps_traces.timestamp -> event_time).
 *
 * A SampleDatasetAdapter for MoveInSync's real anonymised sample file would
 * implement the same DataSourceAdapter interface and do real renaming/
 * joining here instead - nothing downstream would need to change.
 */
@Component
public class SyntheticSourceAdapter implements DataSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyntheticSourceAdapter.class);
    private static final int BATCH_SIZE = 500;

    private final CsvTableLoader csvTableLoader;
    private final JdbcTemplate jdbcTemplate;
    private final Path dataDir;

    public SyntheticSourceAdapter(
            CsvTableLoader csvTableLoader,
            JdbcTemplate jdbcTemplate,
            @Value("${app.data.source-dir:/data}") String dataDir
    ) {
        this.csvTableLoader = csvTableLoader;
        this.jdbcTemplate = jdbcTemplate;
        this.dataDir = Path.of(dataDir);
    }

    @Override
    public String sourceName() {
        return "synthetic";
    }

    @Override
    public IngestionResult load() {
        long start = System.currentTimeMillis();
        Map<String, Integer> rowCounts = new LinkedHashMap<>();

        // Load order respects FK dependencies: drivers -> vendors,
        // employees -> shifts. Fact/event tables have no enforced FKs
        // (see V2__canonical_schema.sql) so their order doesn't matter.
        rowCounts.put("shifts", loadShifts());
        rowCounts.put("vendors", loadVendors());
        rowCounts.put("drivers", loadDrivers());
        rowCounts.put("routes", loadRoutes());
        rowCounts.put("employees", loadEmployees());
        rowCounts.put("trips", loadTrips());
        rowCounts.put("trip_employees", loadTripEmployees());
        rowCounts.put("gps_traces", loadGpsTraces());
        rowCounts.put("delay_records", loadDelayRecords());
        rowCounts.put("safety_incidents", loadSafetyIncidents());
        rowCounts.put("feedback", loadFeedback());
        rowCounts.put("cost_records", loadCostRecords());

        List<DataQualityFinding> findings = runDataQualityChecks();

        long durationMillis = System.currentTimeMillis() - start;
        log.info("Synthetic ingestion complete in {} ms: {}", durationMillis, rowCounts);
        for (DataQualityFinding f : findings) {
            log.warn("Data quality finding: {}.{} = {} ({})", f.sourceTable(), f.issueType(), f.issueCount(), f.detail());
        }

        return new IngestionResult(sourceName(), rowCounts, findings, durationMillis);
    }

    // ---- per-table loaders ----

    private int loadShifts() {
        return csvTableLoader.load(dataDir.resolve("shifts.csv"), "shifts", List.of(
                new ColumnSpec("shift_id", "shift_id", STRING),
                new ColumnSpec("shift_name", "shift_name", STRING),
                new ColumnSpec("scheduled_start", "scheduled_start", STRING),
                new ColumnSpec("scheduled_end", "scheduled_end", STRING)
        ), BATCH_SIZE);
    }

    private int loadVendors() {
        return csvTableLoader.load(dataDir.resolve("vendors.csv"), "vendors", List.of(
                new ColumnSpec("vendor_id", "vendor_id", STRING),
                new ColumnSpec("vendor_name", "vendor_name", STRING),
                new ColumnSpec("mode_types_served", "mode_types_served", STRING),
                new ColumnSpec("contract_start", "contract_start", DATE)
        ), BATCH_SIZE);
    }

    private int loadDrivers() {
        return csvTableLoader.load(dataDir.resolve("drivers.csv"), "drivers", List.of(
                new ColumnSpec("driver_id", "driver_id", STRING),
                new ColumnSpec("vendor_id", "vendor_id", STRING),
                new ColumnSpec("driver_name", "driver_name", STRING),
                new ColumnSpec("rating", "rating", DOUBLE)
        ), BATCH_SIZE);
    }

    private int loadRoutes() {
        return csvTableLoader.load(dataDir.resolve("routes.csv"), "routes", List.of(
                new ColumnSpec("route_id", "route_id", STRING),
                new ColumnSpec("mode", "mode", STRING),
                new ColumnSpec("origin_zone", "origin_zone", STRING),
                new ColumnSpec("destination_zone", "destination_zone", STRING),
                new ColumnSpec("planned_distance_km", "planned_distance_km", DOUBLE)
        ), BATCH_SIZE);
    }

    private int loadEmployees() {
        return csvTableLoader.load(dataDir.resolve("employees.csv"), "employees", List.of(
                new ColumnSpec("employee_id", "employee_id", STRING),
                new ColumnSpec("name", "name", STRING),
                new ColumnSpec("department", "department", STRING),
                new ColumnSpec("shift_id", "shift_id", STRING),      // nullable
                new ColumnSpec("pickup_zone", "pickup_zone", STRING) // nullable
        ), BATCH_SIZE);
    }

    private int loadTrips() {
        return csvTableLoader.load(dataDir.resolve("trips.csv"), "trips", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("route_id", "route_id", STRING),
                new ColumnSpec("driver_id", "driver_id", STRING),
                new ColumnSpec("vendor_id", "vendor_id", STRING),
                new ColumnSpec("shift_id", "shift_id", STRING),
                new ColumnSpec("scheduled_pickup_time", "scheduled_pickup_time", TIMESTAMP),
                new ColumnSpec("actual_pickup_time", "actual_pickup_time", TIMESTAMP),
                new ColumnSpec("scheduled_drop_time", "scheduled_drop_time", TIMESTAMP),
                new ColumnSpec("actual_drop_time", "actual_drop_time", TIMESTAMP),
                new ColumnSpec("distance_km", "distance_km", DOUBLE),
                new ColumnSpec("cost", "cost", DOUBLE),
                new ColumnSpec("mode", "mode", STRING),
                new ColumnSpec("status", "status", STRING)
        ), BATCH_SIZE);
    }

    private int loadTripEmployees() {
        return csvTableLoader.load(dataDir.resolve("trip_employees.csv"), "trip_employees", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("employee_id", "employee_id", STRING)
        ), BATCH_SIZE);
    }

    private int loadGpsTraces() {
        return csvTableLoader.load(dataDir.resolve("gps_traces.csv"), "gps_traces", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("timestamp", "event_time", TIMESTAMP), // CSV header -> renamed DB column
                new ColumnSpec("lat", "lat", DOUBLE),
                new ColumnSpec("lon", "lon", DOUBLE),
                new ColumnSpec("speed", "speed", DOUBLE)
        ), BATCH_SIZE);
    }

    private int loadDelayRecords() {
        return csvTableLoader.load(dataDir.resolve("delay_records.csv"), "delay_records", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("delay_minutes", "delay_minutes", DOUBLE),
                new ColumnSpec("delay_reason_code", "delay_reason_code", STRING) // nullable
        ), BATCH_SIZE);
    }

    private int loadSafetyIncidents() {
        return csvTableLoader.load(dataDir.resolve("safety_incidents.csv"), "safety_incidents", List.of(
                new ColumnSpec("incident_id", "incident_id", STRING),
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("incident_type", "incident_type", STRING),
                new ColumnSpec("severity", "severity", STRING)
        ), BATCH_SIZE);
    }

    private int loadFeedback() {
        return csvTableLoader.load(dataDir.resolve("feedback.csv"), "feedback", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("employee_id", "employee_id", STRING),
                new ColumnSpec("rating", "rating", INTEGER),
                new ColumnSpec("comment_text", "comment_text", STRING)
        ), BATCH_SIZE);
    }

    private int loadCostRecords() {
        return csvTableLoader.load(dataDir.resolve("cost_records.csv"), "cost_records", List.of(
                new ColumnSpec("trip_id", "trip_id", STRING),
                new ColumnSpec("base_fare", "base_fare", DOUBLE),
                new ColumnSpec("surcharge", "surcharge", DOUBLE),
                new ColumnSpec("total_cost", "total_cost", DOUBLE),
                new ColumnSpec("billing_month", "billing_month", STRING)
        ), BATCH_SIZE);
    }

    // ---- post-load data quality checks ----
    // Run against what actually landed in the tables, not the CSVs - this is
    // the "handles messy data gracefully" behaviour: nothing above rejected
    // a row, and here's what we found instead of silently ignoring it.

    private List<DataQualityFinding> runDataQualityChecks() {
        List<DataQualityFinding> findings = new ArrayList<>();

        Integer duplicateTripRows = jdbcTemplate.queryForObject(
                "SELECT count(*) - count(DISTINCT trip_id) FROM trips", Integer.class);
        if (duplicateTripRows != null && duplicateTripRows > 0) {
            findings.add(new DataQualityFinding("trips", "duplicate_trip_id_rows", duplicateTripRows,
                    "trip_id values appearing more than once - likely an upstream export duplication"));
        }

        Integer nullActualPickup = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trips WHERE actual_pickup_time IS NULL", Integer.class);
        if (nullActualPickup != null && nullActualPickup > 0) {
            findings.add(new DataQualityFinding("trips", "missing_actual_pickup_time", nullActualPickup,
                    "no-shows and unlogged pickup events"));
        }

        Integer incompleteRoster = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employees WHERE shift_id IS NULL OR pickup_zone IS NULL", Integer.class);
        if (incompleteRoster != null && incompleteRoster > 0) {
            findings.add(new DataQualityFinding("employees", "incomplete_roster", incompleteRoster,
                    "employees missing a shift or pickup zone assignment"));
        }

        Integer unmatchedTripEmployees = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trip_employees te " +
                "LEFT JOIN employees e ON te.employee_id = e.employee_id " +
                "WHERE e.employee_id IS NULL", Integer.class);
        if (unmatchedTripEmployees != null && unmatchedTripEmployees > 0) {
            findings.add(new DataQualityFinding("trip_employees", "unmatched_employee_id", unmatchedTripEmployees,
                    "trip_employees rows referencing an employee_id not present in employees - roster sync mismatch"));
        }

        Integer completedTripsMissingGps = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM trips t WHERE t.status = 'completed' " +
                "AND NOT EXISTS (SELECT 1 FROM gps_traces g WHERE g.trip_id = t.trip_id)", Integer.class);
        if (completedTripsMissingGps != null && completedTripsMissingGps > 0) {
            findings.add(new DataQualityFinding("gps_traces", "completed_trip_missing_gps", completedTripsMissingGps,
                    "completed trips with zero GPS trace points"));
        }

        return findings;
    }
}
