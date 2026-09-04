package com.moveinsync.agentic.api;

import com.moveinsync.agentic.benchmarking.BenchmarkResult;
import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.benchmarking.Dimension;
import com.moveinsync.agentic.benchmarking.MetricType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Exposes the Phase 3 benchmarking engine. This is deliberately a plain
 * query API, not agentic behaviour on its own - it's the layer Phase 4's
 * attribution logic and Phase 5's agent orchestrator both call into.
 */
@RestController
@RequestMapping("/api/benchmarks")
public class BenchmarkingController {

    private final BenchmarkingService benchmarkingService;

    public BenchmarkingController(BenchmarkingService benchmarkingService) {
        this.benchmarkingService = benchmarkingService;
    }

    /**
     * e.g. GET /api/benchmarks?metric=ON_TIME_ARRIVAL_RATE&dimension=VENDOR&windowDays=28
     * Each result carries value + trend-vs-prior-period + vs-SLA + vs-peer,
     * per the brief's mandatory contextualization requirement.
     */
    @GetMapping
    public List<BenchmarkResult> benchmark(
            @RequestParam MetricType metric,
            @RequestParam(defaultValue = "VENDOR") Dimension dimension,
            @RequestParam(defaultValue = "7") int windowDays,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return benchmarkingService.benchmark(metric, dimension, windowDays, asOf);
    }

    @GetMapping("/metrics")
    public List<Map<String, Object>> listMetrics() {
        return Arrays.stream(MetricType.values())
                .map(m -> {
                    // LinkedHashMap, not Map.of(): slaThreshold is legitimately
                    // null for a couple of metrics, and Map.of() throws on a
                    // null value rather than serializing it as JSON null.
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("key", m.key());
                    row.put("displayName", m.displayName());
                    row.put("higherIsBetter", m.higherIsBetter());
                    row.put("slaThreshold", m.slaThreshold());
                    return row;
                })
                .toList();
    }

    @GetMapping("/latest-data-date")
    public Map<String, Object> latestDataDate() {
        return Map.of("latestDataDate", benchmarkingService.latestDataDate());
    }
}
