package com.moveinsync.agentic.orchestrator;

import java.time.LocalDate;
import java.util.List;

/**
 * The "sense + reason (deterministic)" contract. Every detector is a Spring
 * bean; AgentOrchestrator autowires all of them via List<FindingDetector>,
 * so adding a new finding type (Phase 5's stretch scenarios, or new ones
 * later) is "write one class, don't touch the orchestrator."
 */
public interface FindingDetector {
    List<Finding> detect(LocalDate asOf);
}
