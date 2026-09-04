package com.moveinsync.agentic.orchestrator;

import com.moveinsync.agentic.benchmarking.BenchmarkingService;
import com.moveinsync.agentic.narration.NarrationService;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole sense -> reason -> decide -> act -> remember loop as one
 * explicit, inspectable sequence (see the build plan's Phase 5 - a plain
 * Java pipeline instead of an AWS Step Functions state machine, same named
 * stages). Every FindingDetector bean is autowired automatically via
 * List&lt;FindingDetector&gt;, so this class never changes when a new
 * detector is added.
 *
 * One cycle, per detector, per finding:
 *   sense+reason (deterministic)  -> FindingDetector.detect()
 *   dedup check                   -> AgentAuditRepository.hasRecentOpenFinding()
 *   reason (narrative)            -> NarrationService.narrate()
 *   decide                        -> DecisionPolicy.decide()
 *   act + remember                -> AgentAuditRepository.insertAction()
 *
 * "Act" itself is mocked/logged (see the build plan's rationale - there's
 * no real external system to notify in an offline demo): AUTO_FIRED and
 * LOGGED_INTERNAL actions are considered "acted on" the moment they're
 * persisted (and, from Phase 6, pushed to the Angular feed over WebSocket);
 * PENDING_APPROVAL actions are drafted in full but wait for a human click
 * (see AgentController#approve) before the mocked "send" actuator logs.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** How long a dismissed-or-not finding stays "open" before it's allowed to re-fire. */
    private static final int DEDUP_WINDOW_DAYS = 5;

    private final List<FindingDetector> detectors;
    private final NarrationService narrationService;
    private final DecisionPolicy decisionPolicy;
    private final AgentAuditRepository auditRepository;
    private final BenchmarkingService benchmarkingService;
    private final SimpMessagingTemplate messagingTemplate;

    public AgentOrchestrator(List<FindingDetector> detectors,
                              NarrationService narrationService,
                              DecisionPolicy decisionPolicy,
                              AgentAuditRepository auditRepository,
                              BenchmarkingService benchmarkingService,
                              SimpMessagingTemplate messagingTemplate) {
        this.detectors = detectors;
        this.narrationService = narrationService;
        this.decisionPolicy = decisionPolicy;
        this.auditRepository = auditRepository;
        this.benchmarkingService = benchmarkingService;
        this.messagingTemplate = messagingTemplate;
    }

    public Map<String, Object> runCycle(String runType) {
        LocalDate asOf = benchmarkingService.latestDataDate();
        long runId = auditRepository.startRun(runType);
        log.info("Agent cycle {} (run {}) starting, anchored to data date {}", runType, runId, asOf);

        int findingsDetected = 0;
        int actionsCreated = 0;
        int actionsDeduped = 0;
        List<Map<String, Object>> createdActions = new ArrayList<>();

        for (FindingDetector detector : detectors) {
            List<Finding> findings;
            try {
                findings = detector.detect(asOf);
            } catch (Exception e) {
                log.warn("Detector {} failed, skipping: {}", detector.getClass().getSimpleName(), e.getMessage(), e);
                continue;
            }

            for (Finding finding : findings) {
                findingsDetected++;

                if (auditRepository.hasRecentOpenFinding(finding.dedupKey(), DEDUP_WINDOW_DAYS)) {
                    actionsDeduped++;
                    log.debug("Deduped finding {} - already open within {} days", finding.dedupKey(), DEDUP_WINDOW_DAYS);
                    continue;
                }

                NarrationService.NarrationOutcome narration = narrationService.narrate(finding.factsSummary());
                ActionStatus status = decisionPolicy.decide(finding.findingType());
                long actionId = auditRepository.insertAction(runId, finding, narration, status);
                actionsCreated++;

                log.info("Agent action #{}: [{}] {} -> {}", actionId, status, finding.title(),
                        status == ActionStatus.PENDING_APPROVAL ? "drafted, awaiting approval" : "acted on");

                Map<String, Object> actionSummary = new LinkedHashMap<>();
                actionSummary.put("id", actionId);
                actionSummary.put("findingType", finding.findingType().name());
                actionSummary.put("title", finding.title());
                actionSummary.put("status", status.name());
                actionSummary.put("narrative", narration.text());
                actionSummary.put("narrationProvider", narration.provider());
                createdActions.add(actionSummary);

                // Live delivery (Phase 6): push the instant this action is decided - this is
                // what makes "acts, with minimal human prompting" visible in the UI without
                // the browser ever polling for it.
                messagingTemplate.convertAndSend("/topic/agent-actions", actionSummary);
            }
        }

        auditRepository.finishRun(runId, findingsDetected, actionsCreated, actionsDeduped);
        log.info("Agent cycle {} (run {}) finished: {} findings, {} actions created, {} deduped",
                runType, runId, findingsDetected, actionsCreated, actionsDeduped);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("runType", runType);
        summary.put("asOfDate", asOf.toString());
        summary.put("findingsDetected", findingsDetected);
        summary.put("actionsCreated", actionsCreated);
        summary.put("actionsDeduped", actionsDeduped);
        summary.put("actions", createdActions);

        messagingTemplate.convertAndSend("/topic/agent-runs", summary);
        return summary;
    }
}
