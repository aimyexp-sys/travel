package com.moveinsync.agentic.orchestrator;

/**
 * The three finding types Phase 5's detectors currently produce - each maps
 * to one of the "Agent layer: behavior recap" scenarios in the build plan:
 * VENDOR_SLA_BREACH is scenario 1 (vendor-facing escalation, held for
 * approval), ZONE_SHIFT_DELAY_PATTERN is scenario 2 (internal operational
 * pattern, auto-fired), COST_ANOMALY is the stretch scenario (less certain,
 * logged for internal review rather than escalated).
 */
public enum FindingType {
    VENDOR_SLA_BREACH,
    ZONE_SHIFT_DELAY_PATTERN,
    COST_ANOMALY
}
