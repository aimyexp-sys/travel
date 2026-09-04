package com.moveinsync.agentic.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves the full local stack is wired up correctly: Angular calls this
 * endpoint (via nginx's /api reverse proxy in prod, or the ng-serve dev
 * proxy), and this endpoint round-trips a trivial query to PostgreSQL.
 *
 * This is the "hello world round trip" that closes out Phase 1 - everything
 * from Phase 2 onward (canonical schema, benchmarking, the agent
 * orchestrator) builds on this same connectivity path.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        String databaseStatus;
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            databaseStatus = (result != null && result == 1) ? "UP" : "DOWN";
        } catch (Exception e) {
            databaseStatus = "DOWN";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "moveinsync-agentic-backend");
        body.put("database", databaseStatus);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
