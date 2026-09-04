package com.moveinsync.agentic.benchmarking;

/** One aggregate value for a metric over a date range, for one dimension key. Null value = no data in range. */
public record MetricSample(Double value, int sampleSize) {}
