package com.moveinsync.agentic.api;

import com.moveinsync.agentic.orchestrator.ActionStatus;
import com.moveinsync.agentic.orchestrator.AgentAuditRepository;
import com.moveinsync.agentic.orchestrator.AgentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo's "act layer" surface: trigger a cycle on demand (essential for
 * a reliable live demo rather than waiting on AgentScheduler's clock),
 * inspect what the agent has found/decided, and approve or dismiss the
 * drafted, vendor-facing actions that DecisionPolicy held back for a human
 * click. Phase 6 wires the Angular alert feed to this same data over
 * WebSocket instead of polling.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentOrchestrator orchestrator;
    private final AgentAuditRepository auditRepository;

    public AgentController(AgentOrchestrator orchestrator, AgentAuditRepository auditRepository) {
        this.orchestrator = orchestrator;
        this.auditRepository = auditRepository;
    }

    @PostMapping("/run-cycle")
    public Map<String, Object> runCycle() {
        return orchestrator.runCycle("manual");
    }

    @GetMapping("/actions")
    public List<Map<String, Object>> actions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return auditRepository.listActions(status, limit);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(defaultValue = "20") int limit) {
        return auditRepository.listRuns(limit);
    }

    /**
     * The one human click a PENDING_APPROVAL action is waiting for. The
     * "send" is mocked/logged, not wired to a real vendor/leadership
     * contact - see the architecture doc's future SES/SNS mapping - but
     * this is exactly the point in the pipeline where that would plug in,
     * and a logged "would have sent this" is what a judge actually needs
     * to see to believe the loop closes.
     */
    @PostMapping("/actions/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id) {
        Map<String, Object> action = auditRepository.getAction(id);
        auditRepository.updateStatus(id, ActionStatus.APPROVED);
        if (action != null) {
            log.info("[MOCK SEND] Agent action #{} approved - would notify re: '{}': {}",
                    id, action.get("title"), action.get("narrative"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", "APPROVED");
        body.put("note", "Mocked actuator - logged as sent, no real vendor/leadership contact in this local demo.");
        return body;
    }

    @PostMapping("/actions/{id}/dismiss")
    public Map<String, Object> dismiss(@PathVariable long id) {
        auditRepository.updateStatus(id, ActionStatus.DISMISSED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", "DISMISSED");
        return body;
    }
}
