# Step-by-Step Build Plan: Agentic Mobility Intelligence Layer

Restructured to match the current architecture: fully local Docker deployment (Spring Boot + PostgreSQL + Angular via `docker-compose up`), a plain Java in-process orchestrator instead of AWS Step Functions, and an LLM narration client that talks directly to SarvamAI (primary) with OpenAI as a config-switched fallback. AWS (Step Functions, RDS/Aurora, Bedrock, SES/SNS) remains the described future-production target, not something built for the demo — see the "future AWS mapping" note at the end of each relevant phase.

## Phase 0 — Generate the synthetic dataset

**Goal:** produce a set of CSV/Parquet tables that look like real MoveInSync trip-log exports, with enough structure and enough *deliberate messiness* to exercise the "handles gaps gracefully" good-to-have.

### 0.1 Design the schema first (before writing generator code)
Based on the brief's data description (trips, vendors, drivers, employees, GPS traces, delay records, cost data, employee feedback), a reasonable relational shape:

- **employees** — employee_id, name(anonymised/fake), department, shift_id, pickup_zone
- **shifts** — shift_id, shift_name, scheduled_start, scheduled_end
- **vendors** — vendor_id, vendor_name, mode_types_served (cab/nodal/shuttle), contract_start
- **drivers** — driver_id, vendor_id, driver_name, rating
- **routes** — route_id, mode (cab/nodal/shuttle), origin_zone, destination_zone, planned_distance_km
- **trips** — trip_id, route_id, driver_id, vendor_id, shift_id, scheduled_pickup_time, actual_pickup_time, scheduled_drop_time, actual_drop_time, distance_km, cost, mode, status (completed/no-show/cancelled)
- **trip_employees** — trip_id, employee_id (a trip can carry multiple employees, esp. shuttle/nodal)
- **gps_traces** — trip_id, timestamp, lat, lon, speed (sparse — this is where you inject gaps)
- **delay_records** — trip_id, delay_minutes, delay_reason_code (traffic/driver_late/vehicle_issue/route_change/unknown)
- **safety_incidents** — incident_id, trip_id, incident_type, severity
- **feedback** — trip_id, employee_id, rating (1–5), comment_text, sentiment (optional, derivable)
- **cost_records** — trip_id, base_fare, surcharge, total_cost, billing_month

### 0.2 Decide the time span and volume
Pick something that lets "historical trend" mean something: at minimum 3 months of daily trips, at a scale similar to what's described (hundreds to low thousands of employees/day). You don't need millions of rows — a few thousand trips/day × 90 days is enough to show trend lines, SLA breaches, and vendor comparisons without becoming unwieldy to demo.

### 0.3 Bake in the story you want to tell
Don't generate pure random noise — hand-design 2–3 "narratives" into the data so your agent has something real to discover and narrate:
- One vendor whose OTA (on-time arrival) degrades over the last 2–4 weeks (the "78% vs 85% vs SLA 90%, two vendors responsible" example from the brief).
- One route/zone with a recurring GPS-gap or delay pattern tied to a specific time window (e.g., evening shift, specific pickup zone).
- One cost anomaly (a vendor's per-trip cost creeping up, or a spike in surcharges) uncorrelated with distance/quality.
- Optionally, a safety-incident cluster tied to one driver or vendor.

This matters more than realism of individual fields — the demo lives or dies on whether the agent can *find and explain* a planted signal.

### 0.4 Inject messiness deliberately (for the good-to-have)
- Drop 5–10% of GPS traces entirely for some trips.
- Leave some trip_employees unmatched (employee_id not in employees table, or vice versa).
- Leave some rosters incomplete (shift with no employees assigned).
- A few duplicate trip_ids or missing timestamps.

Document what you injected — you'll want to point at it during the demo to show graceful handling, not just get lucky that your parser didn't crash.

### 0.5 Tooling
Python + `pandas` + `faker` — a single generator script with a fixed random seed for reproducibility. This is a build-time fixture script, not part of the deployed system, so it stays outside the Docker Compose stack entirely: run it once, commit the output CSVs (or a `make generate-data` step) rather than running Python inside a container. Output to a `data/` folder, plus a short `data/SCHEMA.md` documenting each table.

**Deliverable of this phase:** a `data/` folder of tables, a schema doc, and a one-paragraph note on which planted anomalies exist and where (keep this for yourself, not the demo — it's your answer key).

---

## Phase 1 — Local environment scaffolding (Docker Compose)

**Goal:** get `docker-compose up` producing three running, empty-but-wired-together services before writing any business logic — this de-risks the "does it actually run" criterion (25% of the score) earliest, when it's cheapest to fix.

- `docker-compose.yml` with three services: `postgres` (official Postgres image, a named volume, seeded with an empty schema via an init SQL script or Flyway/Liquibase migration on Spring Boot startup), `backend` (Spring Boot app, builds from a Dockerfile, connects to `postgres` by service name), `frontend` (Angular app, either served via a lightweight `nginx` container after `ng build`, or run via `ng serve` for faster dev-loop iteration and swapped to the built version only for the final demo image).
- Spring Boot: set up the project (Spring Web, Spring Data JPA, a Postgres driver, Spring WebSocket) and confirm it connects to the `postgres` container and can run a trivial migration on startup.
- Angular: scaffold the app shell and confirm it can reach the backend's REST API through the Compose network / exposed port.
- Get a "hello world" round trip working end-to-end (Angular calls a backend health endpoint, backend queries Postgres) before moving on — this is the skeleton every later phase hangs off, and problems here are far cheaper to fix now than discovered on demo day.

**Deliverable of this phase:** `docker-compose up` brings up all three services with one command, and a trivial API call round-trips through all of them.

## Phase 2 — Data/ingestion layer
Load the Phase 0 tables into Postgres, joined into an analysis-ready form (trip-level fact table with joins to vendor/driver/employee/shift), and implement the gap-handling logic: flag missing GPS/unmatched records rather than silently dropping or crashing.

### Do you need to build a real ETL from MoveInSync's live systems?
No — and you can't: the brief lists "a full historical data pipeline" and "integration with real vendor systems" under **Not expected**, and the only constraint is "anonymised sample trip-log dataset only — no live system access." Don't spend build time here. There's a smaller, real problem worth solving instead:

**The ETL you actually need to build: sample-file → canonical schema.**
The real anonymised file MoveInSync hands you at the hackathon almost certainly won't use your Phase 0 column names or table boundaries exactly. Rather than writing benchmarking/reasoning/agent code against "whatever the sample file happens to look like," write everything above Phase 0 against the **canonical schema already defined** and add one thin adapter layer whose only job is mapping an input source into that model:

- `SyntheticSourceAdapter` — reads your Phase 0 CSVs and loads them into Postgres (already in canonical shape, effectively a no-op mapper)
- `SampleDatasetAdapter` — reads MoveInSync's real provided file(s) and maps/renames/joins into the same canonical model before loading

This is a ports-and-adapters split: your metrics/benchmarking/reasoning code depends only on the canonical model (a `Trip`, a `DelayRecord`, a `Vendor` — plain Java entities/records), never on a specific file's column names. When the real dataset lands (likely late, possibly day-of), you write one mapping class, not touch anything downstream.

**Future-AWS mapping (narrative only, not built):** this same adapter interface is where a real ETL would plug in — a Kafka-consuming or Glue-based job reading from MoveInSync's dispatch/vendor/HR/billing systems, landing in S3, transformed into the same canonical model, loaded into Aurora PostgreSQL. Worth a sentence in the README/deck; not worth build time.

## Phase 3 — Metrics + benchmarking engine
Compute the core KPIs directly from the fact table in Postgres: OTA%, average delay, cost/trip, cost/km, safety incident rate, feedback score — each sliced by day/week/month, by vendor, by route/zone (Postgres window functions are the right tool here). Build the benchmarking as a first-class function, not an afterthought: every KPI query should return `{value, trend_vs_prior_period, vs_SLA (if defined), vs_peer_average}` as a single object. This is the mandatory contextualization requirement, and building it this way means every later layer (reasoning, alerts, narratives) gets it for free.

## Phase 4 — Reasoning/attribution + narration layer
Two distinct pieces, deliberately kept separate:

1. **Attribution (deterministic, plain Java)** — given a KPI that's breached a threshold or moved meaningfully, decompose it: which vendor(s)/route(s)/shift(s) are the largest contributors to the gap. All arithmetic and percentage-contribution logic lives here, in testable code.
2. **Narration (LLM call)** — a `NarrationClient` interface with two implementations: `SarvamAiNarrationClient` (primary) and `OpenAiNarrationClient` (fallback), both taking the same structured finding as input and returning the same explanatory text ("OTA is 78%, down from 85% last month, against a 90% SLA — Vendor A and Vendor C account for 62% of the shortfall"). Select the active implementation via a Spring config property (`llm.provider=sarvam|openai`) read from an environment variable, so a SarvamAI credits/outage problem mid-hackathon is a one-line change, not a redeploy. **Test the fallback switch once, in advance** — don't discover it doesn't work during judging.

Keep the LLM strictly out of the arithmetic — it only ever sees an already-correct structured object and turns it into a sentence. This split is what keeps inference cost and latency low and predictable, which is directly what's graded.

**Future-AWS mapping:** an additional `BedrockNarrationClient` implementing the same interface is the whole migration — no other code changes.

## Phase 5 — Act layer: the Java orchestrator
Build the sense → reason → decide → act pipeline as an explicit, named sequence inside the Spring Boot app — not AWS Step Functions (see the architecture doc for why: no AWS account/network dependency needed for the demo), but structured so the loop is still visible and inspectable:

- A scheduler (`@Scheduled` or Quartz) triggers pipeline runs on a cadence (e.g., every 15 min for shift-level checks, nightly for vendor rollups, monthly for a leadership rollup) — plus a manual "run agent cycle now" endpoint/admin button, essential for a reliable live demo rather than waiting on a real clock.
- Each pipeline stage (sense/reason/decide/act) is a distinct class/interface, and every run's outcome — what was found, what was decided, what action was taken — is written to a Postgres audit table.
- A **decision-policy stage** tiers actions by autonomy level: low-stakes findings (internal alert) fire automatically; higher-stakes ones (anything that would look vendor- or leadership-facing) are drafted and marked `pending_approval`, surfaced in the UI for one click rather than auto-sent. This is what makes "minimal human prompting" a deliberate design choice rather than "the agent does whatever it wants."
- A dedup check against the audit table (has this specific issue already been flagged recently?) prevents the same finding from re-firing every cycle.
- Actuators for the demo are **mocked/logged**, not wired to real email/SMS providers — there's no real external system to notify in an offline demo, and a logged "would have sent this" is just as convincing as an actual send for evaluation purposes. Push the result straight to the Angular alert feed instead (Phase 6).

This phase is the single most important box to check for the "not passive/query-only" mandatory requirement — get a threshold-based trigger firing (even with a templated explanation, before Phase 4's narration is polished) as early as possible.

**Future-AWS mapping:** this pipeline becomes an AWS Step Functions state machine with the same named stages, triggered by EventBridge; actuators become SES/SNS.

## Phase 6 — Persona-specific output surface + live delivery
Pick one primary persona (transport & facilities head is the strongest default given the bonus criteria around leadership-ready, forward-without-rework output) and build one output artifact tuned to them: a narrative brief for the strategic head, or a short actionable alert feed for the operational manager.

- Wire the Angular frontend's alert feed and "agent activity log" to a Spring WebSocket (STOMP) channel, so a finding from Phase 5 appears in the UI the instant it's decided — with no polling, no page refresh, no user action. This is the clearest live demonstration of "acts, with minimal human prompting" a judge will see.
- Make at least one output "leadership-ready": formatted, no jargon, no raw numbers without context, ideally exportable/copyable as something that could genuinely be forwarded.

**Future-AWS mapping:** the same WebSocket contract, just fronted by API Gateway's WebSocket API in production instead of Spring's embedded support — no redesign needed.

## Phase 7 — Optional: conversational drill-down
Layer a chat interface over the same attribution/narration services from Phase 4, so a user can ask "why did OTA drop this week" and get the same benchmarking + attribution logic, interactively, through the same `NarrationClient`. This is what lets you claim you combined multiple output forms (proactive alerting + automated narrative + conversational agent) for the good-to-have credit, without building three separate pipelines — it's a thin UI + one new endpoint over infrastructure you already have.

## Agent layer: behavior recap (local architecture)

### Component mapping

| Role in the sense→reason→act loop | Component | Local realization | Future-AWS equivalent |
|---|---|---|---|
| Sense (trigger) | Scheduler | Spring `@Scheduled`/Quartz, plus a manual "run now" endpoint | EventBridge Scheduler rules |
| Sense (data access) | Ingestion/metrics service | Spring Boot service reading Postgres | Same, against RDS/Aurora |
| Reason (deterministic) | Benchmarking + attribution service | Plain Java, Postgres window-function queries | Unchanged |
| Reason (narrative) | LLM narration | `NarrationClient` → SarvamAI (primary) / OpenAI (fallback), config-switched | `BedrockNarrationClient`, same interface |
| Decide (policy) | Decision/orchestrator stage | In-process Java pipeline, stages as named classes | Step Functions state machine |
| Act | Actuators | Mocked/logged, pushed to Angular via WebSocket | SES (email), SNS (push) |
| Memory / dedup / audit | State store | A Postgres audit table | Same table, or DynamoDB if write volume justified it later |
| Delivery | Angular frontend | Dashboard, alert feed, agent activity log, optional chat — WebSocket-driven | Same UI, served via S3 + CloudFront in production |

### Behavioral loop
Each cycle: **sense** (scheduled scan or manual trigger) → **reason** (compute benchmarked KPIs, run attribution if breached) → **decide** (severity/urgency policy: log-only vs. notify vs. escalate, with N-consecutive-breach checks to avoid noise) → **act** (push to feed / draft for approval) → **remember** (write to the audit table so the same issue isn't re-flagged every cycle). The "decide" stage doing real tiering — not firing on every threshold crossing — is what demonstrates judgment rather than a trigger-happy system.

### 2–3 scenarios: acting with minimal human in the loop

**Scenario 1 — Vendor SLA breach → drafted escalation, held for one click**
The nightly pipeline run recomputes 7-day OTA per vendor and finds Vendor A at 78%, down from 85% a month ago, against a 90% SLA, sustained for 3+ consecutive days (the decide stage specifically checks for sustained trend, not a one-off blip). The attribution service decomposes the delay by reason code and finds "driver_late" dominant for Vendor A specifically. The narration client turns this into a two-sentence explanation. Because this is vendor-facing, the decide stage marks it `pending_approval` rather than auto-sending — it's drafted in full and surfaced in the Angular activity log for one click. No one had to notice the dip, pull the report, or write anything; a human only clicks send.

**Scenario 2 — Shift-level delay ripple → proactive line-manager alert**
Every 15 minutes during shift-start windows, the pipeline checks in-progress trips against scheduled pickup times. It detects 12 employees assigned to the 9 AM shift, all from the same pickup zone, running 15+ minutes late, and cross-references a vehicle-breakdown flag logged against that zone's depot. This is internal/low-stakes, so the decide stage fires it automatically — it appears in the line manager's alert feed over WebSocket the instant it's decided, with who's affected, the likely cause, and a suggested action. This is the clearest "minimal human prompting" case in the brief's own framing: a line manager who never opened the app still knows what's happening to their shift before it fully unfolds.

**Scenario 3 — Monthly leadership narrative, auto-compiled and forward-ready**
A monthly scheduled run pulls last month's OTA/cost/safety/experience metrics, benchmarks each against the prior month and SLA, identifies the top 3 problem areas and top 2 wins, and has the narration client render it into a structured brief using a fixed template (not free-form, which keeps it "forward without rework"). The finished brief lands in the transport & facilities head's dashboard (and, in the future-AWS version, would be emailed via SES) ready to read. The only human action is deciding whether to forward it to their own leadership — exactly the bonus criterion the brief names.

A stretch fourth: a **cost-anomaly scenario** where the pipeline continuously compares cost/km per vendor against the fleet average and, on detecting a rise with no corresponding distance/quality change, opens an internal "billing review" row (surfaced in the dashboard) rather than sending anything — showing the decide stage choosing a *lower-stakes* action when the finding is less certain, versus full escalation when it's confident. Worth having in the demo since it proves the decision layer reasons about *what* to do, not just *whether* to fire a fixed action.

## Phase 8 — Packaging for evaluation
- Architecture diagram (canonical data model → benchmarking engine → attribution/narration → Java orchestrator → Angular delivery), plus the "future AWS deployment" box from the architecture document, so the deployability bonus is covered without having built it.
- README with `docker-compose up` as the single setup instruction, plus how to regenerate/replace the dataset and how to switch the LLM provider (`llm.provider` env var).
- Sample inputs/outputs checked into the repo (a couple of example alerts/narratives, ideally including the ones triggered by your planted anomalies).
- Presentation deck built around the before/after story: "a metric without context is just a number" → show the dumb version, then your agent's version.

---

## Suggested build order if time is tight
1. Phase 0 (dataset) — do this first.
2. Phase 1 (Docker Compose skeleton) — get all three services running and talking to each other before writing business logic; cheapest time to catch infra problems.
3. Phase 2 + 3 together (ingestion + benchmarking) — this alone satisfies the mandatory contextualization requirement and gives you something demoable in Postgres.
4. Phase 5's trigger mechanics (a threshold check firing on a schedule) before Phase 4's narration is polished — even a templated explanation satisfies "acts"; upgrade to real narration once the loop works end-to-end.
5. Phase 4 (attribution + LLM narration, with the SarvamAI/OpenAI switch tested at least once).
6. Phase 6 (WebSocket delivery + persona output polish) — this is where a lot of the 35%-weighted business-impact score lives, worth real time.
7. Phase 7 (chat) — only if time remains; it's good-to-have, not mandatory.
8. Phase 8 (packaging) — don't leave this to the last hour; the deck and diagram are graded criteria, not garnish.
