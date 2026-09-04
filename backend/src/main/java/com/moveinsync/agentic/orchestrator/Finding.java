package com.moveinsync.agentic.orchestrator;

/**
 * Output of the "sense + reason (deterministic)" stages, before narration
 * or decision policy touch it. dedupKey is what the orchestrator checks
 * against the audit table to avoid re-flagging the same underlying issue
 * every cycle - it should be stable across runs for the "same" finding
 * (e.g. "vendor-sla:V1") and distinct across different ones.
 */
public record Finding(
        FindingType findingType,
        String dedupKey,
        String vendorId,   // nullable
        String zone,       // nullable
        String shiftId,    // nullable
        String title,
        String factsSummary  // deterministic text - the only thing NarrationService ever sees
) {}
