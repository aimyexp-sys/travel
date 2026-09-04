package com.moveinsync.agentic.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "sense" trigger from the build plan's component-mapping table:
 * Spring's own scheduler standing in for AWS EventBridge. Two cadences,
 * matching Phase 5's spec - nightly for vendor/cost rollups, frequent for
 * operational/structural checks - plus AgentController#runCycle for a
 * manual "run now" trigger, which is what the live demo actually uses
 * (waiting on a real clock during judging would be a bad idea).
 *
 * Note: the dataset is anchored to its own latest date (see
 * BenchmarkingService.latestDataDate()), not the wall clock, since it's a
 * fixed historical sample - so on a long-running demo instance, repeated
 * scheduled runs mostly exercise AgentOrchestrator's dedup path rather than
 * finding new things every time. That's the correct behavior: the same
 * underlying issue should not be re-flagged every 15 minutes.
 */
@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    private final AgentOrchestrator orchestrator;

    public AgentScheduler(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** Nightly vendor SLA + cost rollup cadence. */
    @Scheduled(cron = "0 0 2 * * *")
    public void nightlyCycle() {
        log.info("Scheduled agent cycle: nightly");
        orchestrator.runCycle("scheduled-nightly");
    }

    /** Frequent operational/structural check cadence (shift-level, in the build plan's framing). */
    @Scheduled(fixedRate = 15 * 60 * 1000, initialDelay = 60 * 1000)
    public void frequentCycle() {
        log.info("Scheduled agent cycle: frequent");
        orchestrator.runCycle("scheduled-frequent");
    }
}
