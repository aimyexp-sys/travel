package com.moveinsync.agentic.orchestrator;

import com.moveinsync.agentic.narration.NarrationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * The "memory / dedup / audit" component from the build plan's agent
 * component-mapping table - a plain Postgres table (agent_runs / agent_actions,
 * see V3__agent_audit.sql), queried directly via JdbcTemplate rather than
 * JPA to keep this consistent with the rest of the app's data-access style.
 */
@Repository
public class AgentAuditRepository {

    private final JdbcTemplate jdbc;

    public AgentAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long startRun(String runType) {
        return jdbc.queryForObject(
                "INSERT INTO agent_runs (run_type) VALUES (?) RETURNING id",
                Long.class, runType);
    }

    public void finishRun(long runId, int findingsDetected, int actionsCreated, int actionsDeduped) {
        jdbc.update(
                "UPDATE agent_runs SET finished_at = now(), findings_detected = ?, actions_created = ?, actions_deduped = ? WHERE id = ?",
                findingsDetected, actionsCreated, actionsDeduped, runId);
    }

    /**
     * Dedup check: has this exact finding (by dedupKey) already been raised
     * and not dismissed within the window? Prevents the same structural
     * pattern or vendor breach from re-firing (and re-narrating, burning
     * LLM calls) every single cycle.
     */
    public boolean hasRecentOpenFinding(String dedupKey, int windowDays) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_actions WHERE dedup_key = ? AND status <> 'DISMISSED' " +
                        "AND created_at >= now() - (? || ' days')::interval",
                Integer.class, dedupKey, windowDays);
        return count != null && count > 0;
    }

    public long insertAction(long runId, Finding finding, NarrationService.NarrationOutcome narration, ActionStatus status) {
        return jdbc.queryForObject(
                """
                INSERT INTO agent_actions
                    (run_id, finding_type, dedup_key, vendor_id, zone, shift_id, title, facts_summary, narrative, narration_provider, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                runId, finding.findingType().name(), finding.dedupKey(), finding.vendorId(), finding.zone(), finding.shiftId(),
                finding.title(), finding.factsSummary(), narration.text(), narration.provider(), status.name());
    }

    public List<Map<String, Object>> listActions(String statusFilter, int limit) {
        if (statusFilter != null && !statusFilter.isBlank()) {
            return jdbc.queryForList(
                    "SELECT * FROM agent_actions WHERE status = ? ORDER BY created_at DESC LIMIT ?",
                    statusFilter.toUpperCase(), limit);
        }
        return jdbc.queryForList("SELECT * FROM agent_actions ORDER BY created_at DESC LIMIT ?", limit);
    }

    public Map<String, Object> getAction(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM agent_actions WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int updateStatus(long actionId, ActionStatus newStatus) {
        return jdbc.update(
                "UPDATE agent_actions SET status = ?, resolved_at = now() WHERE id = ?",
                newStatus.name(), actionId);
    }

    public List<Map<String, Object>> listRuns(int limit) {
        return jdbc.queryForList("SELECT * FROM agent_runs ORDER BY started_at DESC LIMIT ?", limit);
    }
}
