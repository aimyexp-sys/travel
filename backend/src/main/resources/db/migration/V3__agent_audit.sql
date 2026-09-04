-- Phase 5: audit trail for the agent orchestrator's sense->reason->decide->act
-- cycles. agent_runs is one row per pipeline execution (manual or scheduled);
-- agent_actions is one row per finding that survived dedup, carrying the
-- decision (status) and the narrated text - this is what the Angular
-- activity log (Phase 6) will read from, and what proves the agent acted
-- rather than just computed a number.

CREATE TABLE agent_runs (
    id BIGSERIAL PRIMARY KEY,
    run_type VARCHAR(40) NOT NULL,
    started_at TIMESTAMP NOT NULL DEFAULT now(),
    finished_at TIMESTAMP,
    findings_detected INTEGER NOT NULL DEFAULT 0,
    actions_created INTEGER NOT NULL DEFAULT 0,
    actions_deduped INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE agent_actions (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES agent_runs(id),
    finding_type VARCHAR(60) NOT NULL,
    dedup_key VARCHAR(200) NOT NULL,
    vendor_id VARCHAR(20),
    zone VARCHAR(100),
    shift_id VARCHAR(20),
    title VARCHAR(300) NOT NULL,
    facts_summary TEXT NOT NULL,
    narrative TEXT,
    narration_provider VARCHAR(40),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at TIMESTAMP
);

CREATE INDEX idx_agent_actions_dedup_key ON agent_actions (dedup_key, created_at);
CREATE INDEX idx_agent_actions_status ON agent_actions (status);
