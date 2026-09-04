package com.moveinsync.agentic.ingestion;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic CSV -> Postgres table loader shared by every source adapter
 * (SyntheticSourceAdapter today; a future SampleDatasetAdapter for the real
 * MoveInSync file). Column mapping and type conversion are supplied by the
 * caller per table, so this class has no knowledge of the canonical schema
 * itself - see SyntheticSourceAdapter for the actual table specs.
 */
@Component
public class CsvTableLoader {

    public enum ColumnType { STRING, INTEGER, DOUBLE, TIMESTAMP, DATE }

    public record ColumnSpec(String csvHeader, String dbColumn, ColumnType type) {}

    private final JdbcTemplate jdbcTemplate;

    public CsvTableLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads {@code csvFile} into {@code tableName} using {@code columns} to
     * map CSV headers to DB columns (in insert order). Missing/blank CSV
     * values become SQL NULL - callers should not rely on this method to
     * validate referential integrity; that's the adapter's job, done as a
     * post-load pass so a bad row never aborts the whole batch.
     *
     * @return number of rows read from the CSV (and inserted)
     */
    public int load(Path csvFile, String tableName, List<ColumnSpec> columns, int batchSize) {
        if (!Files.exists(csvFile)) {
            throw new IllegalStateException("CSV file not found: " + csvFile.toAbsolutePath());
        }

        String columnList = String.join(", ", columns.stream().map(ColumnSpec::dbColumn).toList());
        String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        String sql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")";

        int total = 0;
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build();

        try (Reader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            List<Object[]> batch = new ArrayList<>(batchSize);
            for (CSVRecord record : parser) {
                Object[] row = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    ColumnSpec col = columns.get(i);
                    String raw = record.isMapped(col.csvHeader()) ? record.get(col.csvHeader()) : null;
                    row[i] = convert(raw, col.type());
                }
                batch.add(row);
                total++;
                if (batch.size() >= batchSize) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read CSV: " + csvFile, e);
        }
        return total;
    }

    private Object convert(String raw, ColumnType type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (type) {
            case STRING -> raw;
            case INTEGER -> Integer.valueOf(raw);
            case DOUBLE -> Double.valueOf(raw);
            case TIMESTAMP -> Timestamp.valueOf(LocalDateTime.parse(raw));
            case DATE -> Date.valueOf(LocalDate.parse(raw));
        };
    }
}
