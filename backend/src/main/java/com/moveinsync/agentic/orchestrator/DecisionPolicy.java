package com.moveinsync.agentic.orchestrator;

import org.springframework.stereotype.Component;

/**
 * The "decide" stage: maps a finding type to how autonomously it's allowed
 * to act. This is what makes "minimal human prompting" a deliberate design
 * choice rather than a trigger-happy agent - low-stakes/internal findings
 * fire on their own, anything vendor- or leadership-facing is drafted in
 * full but held for one human click, and low-confidence findings (no fixed
 * SLA to point to) are logged for review rather than escalated either way.
 * A real policy engine could weigh severity/confidence per-instance; a
 * fixed per-type mapping is the right amount of complexity for this demo
 * and is trivial to explain to a judge.
 */
@Component
public class DecisionPolicy {

    public ActionStatus decide(FindingType findingType) {
        return switch (findingType) {
            case VENDOR_SLA_BREACH -> ActionStatus.PENDING_APPROVAL;      // vendor-facing, held for one click
            case ZONE_SHIFT_DELAY_PATTERN -> ActionStatus.AUTO_FIRED;     // internal/operational, low stakes
            case COST_ANOMALY -> ActionStatus.LOGGED_INTERNAL;            // less certain, billing review only
        };
    }
}
