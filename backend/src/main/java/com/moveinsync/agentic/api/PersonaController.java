package com.moveinsync.agentic.api;

import com.moveinsync.agentic.persona.LeadershipBriefService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Persona-specific output surface (Phase 6) - the transport & facilities head's leadership brief. */
@RestController
@RequestMapping("/api/persona")
public class PersonaController {

    private final LeadershipBriefService leadershipBriefService;

    public PersonaController(LeadershipBriefService leadershipBriefService) {
        this.leadershipBriefService = leadershipBriefService;
    }

    @GetMapping("/leadership-brief")
    public Map<String, Object> leadershipBrief() {
        return leadershipBriefService.buildBrief();
    }
}
