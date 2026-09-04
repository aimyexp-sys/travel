package com.moveinsync.agentic.orchestrator;

/**
 * Where a finding sits in the decide->act lifecycle.
 *
 * AUTO_FIRED and LOGGED_INTERNAL are terminal states the decide stage picks
 * directly - the action already happened (posted to the internal feed / a
 * review queue), no human click required. PENDING_APPROVAL is a drafted,
 * held action awaiting one human click, which then moves it to APPROVED
 * (the mocked "would have sent this" actuator fires) or DISMISSED.
 */
public enum ActionStatus {
    AUTO_FIRED,
    LOGGED_INTERNAL,
    PENDING_APPROVAL,
    APPROVED,
    DISMISSED
}
