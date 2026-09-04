package com.moveinsync.agentic.ingestion;

/**
 * Loads an external source into the canonical schema (see
 * /data/SCHEMA.md and V2__canonical_schema.sql). Everything above this
 * layer - benchmarking, reasoning, the agent orchestrator - depends only on
 * the canonical tables, never on a specific source's file shape.
 *
 * SyntheticSourceAdapter is the only implementation today (loads Phase 0's
 * generated CSVs, which are already in canonical shape - effectively a
 * no-op mapper). A SampleDatasetAdapter implementing this same interface is
 * the whole change needed to switch to MoveInSync's real anonymised sample
 * file once it's available: everything downstream is unaffected.
 */
public interface DataSourceAdapter {

    /** Short identifier stored in ingestion_runs.source, e.g. "synthetic". */
    String sourceName();

    /** Loads all tables and returns a summary (row counts + data-quality findings). */
    IngestionResult load();
}
