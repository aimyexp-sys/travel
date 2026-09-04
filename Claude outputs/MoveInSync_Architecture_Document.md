# Architecture Document — Agentic Intelligence & Reporting Layer for Enterprise Mobility

## 1. Purpose and scope

This document defines the high-level architecture for the MoveInSync hackathon submission, evaluates the alternative design options considered at each major decision point, and states the chosen approach with justification. It is written to double as the "Architecture diagram" + supporting narrative deliverable required in Section 10 of the problem statement, and to directly support the evaluation criteria weighted on agentic design (20%) and architecture/code quality (20%).

Stack: **Java (Spring Boot), Angular, PostgreSQL** — all deployed locally via Docker Compose for the demo. AWS remains the described target for a real deployment (Section 8), but the submission itself does not require an AWS account, network access, or cloud credentials to run.

## 2. Approach summary

The system is built as four layers, each independently swappable, connected by a canonical domain model rather than by any one data source's raw shape:

1. **Data layer** — canonical schema (trips, vendors, drivers, employees, delay records, GPS traces, cost, feedback) populated via a pluggable adapter (synthetic generator now, real MoveInSync sample later, described-only live-system ETL for the deployability story), stored in **PostgreSQL**.
2. **Reasoning layer** — deterministic benchmarking (trend/SLA/peer) and attribution, computed in Java; an LLM is invoked only to narrate an already-computed structured finding, never to do arithmetic over raw data. The LLM call goes through a swappable client interface — **SarvamAI as the primary provider, OpenAI as a same-interface fallback** — selected by configuration, not code change.
3. **Agent/orchestration layer** — a sense → reason → decide → act loop implemented as a **plain Java orchestrator inside the Spring Boot app** (scheduled triggers via `@Scheduled`/Quartz, explicit pipeline stages, every step logged to a Postgres audit table), with a decision policy that tiers actions by autonomy level (auto-fire vs. auto-draft-and-hold). AWS Step Functions is documented as the equivalent production target, not built for the demo (Section 4.2 explains why).
4. **Delivery layer** — an Angular frontend (persona dashboard, alert feed, agent activity log, optional chat) served alongside the backend, with alerts pushed over a **Spring WebSocket (STOMP)** channel so the UI updates without polling; outbound channels for the demo (email/notification) are logged/mocked rather than wired to real SES/SNS, since there's no external system to actually notify in an offline demo.

The whole stack — Spring Boot app, PostgreSQL, Angular — runs as three containers via a single `docker-compose up`, with no external network dependency required for the core sense→reason→act loop (only the LLM narration call needs internet, to reach SarvamAI/OpenAI).

## 3. High-level design

```
                    ┌─────────────────────────────────────────────┐
                    │              DATA SOURCES                    │
                    │  Phase-0 synthetic CSVs  │  Real sample file  │
                    └──────────────┬────────────────────┬──────────┘
                                   │                    │
                          SyntheticSourceAdapter   SampleDatasetAdapter
                                   │                    │
                                   └─────────┬──────────┘
                                              ▼
                              ┌───────────────────────────┐
                              │   Canonical domain model    │
                              │ (Trip, Vendor, DelayRecord,  │
                              │  Employee, Shift, Feedback…) │
                              │   stored in PostgreSQL         │
                              │   (Docker container)           │
                              └───────────────┬───────────┘
                                              ▼
                        ┌─────────────────────────────────────┐
                        │   Benchmarking & Attribution engine    │
                        │  (Java, deterministic — trend / SLA /  │
                        │   peer deltas, root-cause decomposition)│
                        └───────────────────┬─────────────────┘
                                            ▼
                    ┌───────────────────────────────────────────┐
                    │   Agent orchestrator (Java, in-process)       │
                    │   sense → reason → decide → act, triggered by │
                    │   @Scheduled/Quartz or threshold breach        │
                    │   — logs every step to a Postgres audit table  │
                    └───────┬───────────────────────────┬─────────┘
                            ▼                           ▼
                 ┌───────────────────────┐    ┌──────────────────────┐
                 │  Narration — LLM client  │    │   Actuators            │
                 │  interface:                │    │  (mocked/logged for   │
                 │  SarvamAI (primary) ↔      │    │   the offline demo;    │
                 │  OpenAI (fallback), same    │    │   SES/SNS in prod)     │
                 │  interface, config-switched  │    │                        │
                 └───────────────────────┘    └──────────────────────┘
                            │                           │
                            └─────────────┬─────────────┘
                                          ▼
                        ┌──────────────────────────────────┐
                        │   Angular frontend (per persona)    │
                        │  dashboard | alert feed | agent log │
                        │  | optional chat drill-down          │
                        │  — live updates via Spring WebSocket  │
                        └──────────────────────────────────┘

        All three boxes (Spring Boot app, PostgreSQL, Angular) run as
        Docker Compose services on one machine — only the LLM call
        needs external network access.
```

The audit/dedup state (what's already been alerted, full decision log) is a table in the same PostgreSQL instance — one datastore for the whole demo, not a second store, which was the earlier design's DynamoDB split.

## 4. Design alternatives evaluated

### 4.1 Reasoning: rule-based vs. LLM-only vs. hybrid

| Option | Pros | Cons |
|---|---|---|
| **Rule-based only** (fixed thresholds, templated sentences) | Cheapest, fastest, fully deterministic, zero inference cost, easy to test | Reads as "decorating with if-statements," not genuinely agentic; brittle to anything not pre-anticipated; weak on the "agentic design" criterion which explicitly asks whether AI is solving a genuine problem |
| **LLM-only** (feed raw/aggregated data to an LLM and ask it to find and explain issues) | Flexible, can surface unanticipated patterns, less code to write | Expensive and slow at "enterprise volumes" (directly penalized by the cost/latency criterion); non-deterministic arithmetic is a correctness risk; harder to test/demo reliably |
| **Hybrid (chosen)** — deterministic computation for benchmarking/attribution, LLM only for narration of an already-correct structured result | Correctness guaranteed by code for the numbers; LLM cost stays low and bounded (one short call per finding, on structured input, not raw rows); directly demonstrates the cost/latency discipline the rubric rewards; still genuinely agentic — the LLM's reasoning contribution is real, just scoped correctly | Slightly more upfront design work to define the interface between the deterministic and LLM stages |

**Decision:** hybrid — unchanged from the earlier design; this choice is independent of cloud vs. local deployment.

### 4.2 Orchestration: custom Java orchestrator vs. AWS Step Functions vs. self-hosted workflow engine (Temporal)

| Option | Pros | Cons |
|---|---|---|
| **Custom Java orchestrator (chosen)** — `@Scheduled`/Quartz triggers, an explicit pipeline of named stages (sense → reason → decide → act), every stage's outcome written to a Postgres audit table, surfaced live in the Angular "agent activity log" | Zero external dependencies — runs entirely inside the same Docker container as the rest of the backend; nothing to deploy, authenticate, or keep online for the demo; still makes the loop *explicit and inspectable* (the whole point of choosing Step Functions originally) because the pipeline stages are named classes/interfaces and every run is logged and visible in the UI | Doesn't get AWS's built-in retry/state-persistence machinery for free — acceptable at hackathon scale, would need hardening for real production use |
| **AWS Step Functions** | The agent loop becomes a visual AWS-native artifact; built-in retry/error handling; natural fit for a real EventBridge-triggered production deployment | Requires an AWS account, IAM roles for the state machine's execution role, the state machine defined in Amazon States Language and deployed (console/CloudFormation/CDK), an EventBridge rule to trigger it, and a live network path to AWS during the demo itself — real setup and a real risk if connectivity or account access hiccups while judges are watching. A local emulation exists (LocalStack), but full Step Functions support is a LocalStack **Pro** feature, adding cost and complexity rather than removing it. |
| **Temporal** (open-source durable workflow engine, Docker Compose deployable, own web UI showing workflow executions, Java SDK) | No AWS dependency at all, but keeps a Step-Functions-like visual: a dashboard showing each agent run's steps and state — arguably a stronger live-demo visual than Step Functions since it's already local | One more system to learn/wire up in the Docker Compose stack under time pressure; the payoff (a nicer visualization) is a "good-to-have" polish item, not something any evaluation criterion asks for directly |

**Decision:** custom Java orchestrator for the actual submission, since it removes the AWS-account/network dependency entirely while preserving the property that mattered (an explicit, inspectable, logged agent loop — delivered via the Postgres audit table + Angular activity log instead of an AWS console). Step Functions is retained in Section 8 as the described production-deployment target, using the exact same pipeline-stage boundaries, so the deployability narrative doesn't lose anything. Temporal is noted as a possible upgrade if there's spare time and the team wants a more visually polished local orchestration demo, but isn't the default.

### 4.3 LLM provider: AWS Bedrock vs. self-hosted OSS model vs. direct external API

| Option | Pros | Cons |
|---|---|---|
| **AWS Bedrock** | Pay-per-token, no infra, multiple model choices | Requires AWS credentials/region setup purely to make LLM calls — an unnecessary cloud dependency once the rest of the stack is local; adds friction to `docker-compose up` working out of the box |
| **Self-hosted open-source model** | No per-vendor API dependency | GPU provisioning/latency tuning is real infrastructure work disproportionate to a hackathon timeline; explicitly the kind of production-hardening the brief says isn't expected |
| **Direct external LLM API — SarvamAI primary, OpenAI fallback (chosen)** | Simplest possible integration (an HTTPS call from Java, no SDK/IAM setup); matches "demo environment deploying locally is preferred" — the only external dependency in the whole stack; a swappable-provider interface means if SarvamAI credits run out mid-hackathon, switching to OpenAI is a config change, not a code change | Two providers to test against instead of one; API keys need to be handled as environment variables/secrets even in the demo (not committed to the repo) |

**Decision:** direct external API. Implement one `NarrationClient` interface with `SarvamAiNarrationClient` and `OpenAiNarrationClient` implementations, both taking the same structured-finding input and returning the same explanatory-text output; select the active implementation via a Spring `@ConditionalOnProperty` (e.g., `llm.provider=sarvam` vs `llm.provider=openai`) so a credentials/credit problem during the hackathon is a one-line config/env-var change, not a redeploy. Keep the request/response contract provider-agnostic (plain prompt in, plain text out) so adding a third provider later is equally cheap.

### 4.4 Data store: PostgreSQL vs. MySQL vs. cloud-managed (RDS/Aurora/DynamoDB)

| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL (chosen)** | Official Docker image, trivial `docker-compose` service; strong window-function/aggregation support, which is exactly what the benchmarking layer's trend/peer/SLA queries need; JSONB available if any semi-structured field (e.g., raw feedback metadata) doesn't fit the relational model cleanly; same engine the earlier AWS-targeted design already assumed (Aurora Postgres-compatible), so the future-AWS story in Section 8 requires no schema rework | None significant for this use case |
| **MySQL** | Equally easy to run in Docker, very familiar | Slightly weaker analytical/window-function ergonomics for the trend/benchmarking queries than Postgres; no material advantage here to offset that |
| **Cloud-managed (RDS/Aurora/DynamoDB)** | Matches a real production deployment exactly | Directly conflicts with "demo environment deploying locally is preferred" — requires an AWS account and network access just to store data, for no benefit during a local demo |

**Decision:** PostgreSQL via Docker Compose, used for both the canonical data model and the agent's audit/dedup state (one database, no second store needed at this scale — the earlier DynamoDB split was solving a production-scale problem that doesn't exist yet). This also means the future-AWS migration path is "point the same schema at Aurora PostgreSQL," not a data-model rewrite.

### 4.5 Frontend delivery: polling vs. WebSocket/push

| Option | Pros | Cons |
|---|---|---|
| **Polling** | Trivial, no new infrastructure | Undercuts the demo's ability to show proactive behavior — alerts look "checked for" rather than "pushed" |
| **WebSocket / push (chosen)** — Spring's built-in WebSocket/STOMP support, no AWS API Gateway needed since everything is local | Visibly shows an alert appearing in the Angular UI the instant the agent decides to act, with zero user interaction — strong, cheap proof of "acts, with minimal human prompting" for a judge watching live; fully local, no extra Docker service beyond the Spring Boot app itself | Slightly more setup than a REST poll, but Spring's WebSocket support is mature and well-documented, low actual risk |

**Decision:** unchanged — push for the alert feed/activity log, plain REST for historical/report views — except the transport is now Spring's own WebSocket support rather than an AWS-managed WebSocket API, which removes a cloud dependency this decision didn't need in the first place.

### 4.6 Deployment topology: monolith vs. microservices vs. modular monolith

| Option | Pros | Cons |
|---|---|---|
| **Single Spring Boot monolith** | Fastest to build/demo | Weaker "deployable into an existing platform" story |
| **Full microservices** | Matches a large-scale production deployability narrative | Real risk of running out of build time; needless operational overhead for a local Docker demo |
| **Modular monolith (chosen)** — one Spring Boot deployable, internally layered (data / benchmarking / orchestration / API), packaged with Postgres and Angular as three Docker Compose services | Reliable to get working end-to-end in hackathon time; the internal layer boundaries are exactly where a real deployment would cut services apart later (Section 8); three-container Compose setup is also simply what "runs locally" means in practice here | Less impressive on paper than "N microservices," but far more likely to actually run when a judge types `docker-compose up` |

**Decision:** unchanged — modular monolith, now concretely expressed as a 3-service Docker Compose stack (`backend`, `postgres`, `frontend`).

## 5. Data flow for one representative scenario

Vendor SLA breach → autonomous escalation:

1. A Spring `@Scheduled` job (nightly, configurable) invokes the agent orchestrator's pipeline.
2. **Sense/reason stage** calls the benchmarking service (Java, reading PostgreSQL) → computes 7-day OTA per vendor with trend/SLA deltas.
3. A filter stage keeps vendors breaching SLA on a sustained basis, checked against the Postgres audit table to avoid re-firing on an already-open issue.
4. For each breach, the attribution stage decomposes delay by reason code and vendor contribution.
5. The structured finding is passed to the active `NarrationClient` (SarvamAI, or OpenAI if configured as fallback) → returns a short explanatory sentence.
6. A decision-policy stage tiers the action: internal-only findings are logged and pushed straight to the Angular alert feed; vendor-facing findings are drafted and flagged `pending_approval` in the audit table, surfaced in the "agent activity log" for one-click confirmation (the actual send is mocked/logged for the offline demo, described as an SES call in Section 8).
7. Every stage's outcome is written to the audit table, and the resulting alert appears in the Angular feed in real time over the WebSocket channel.

## 6. Non-functional considerations

- **Cost:** the only per-request LLM cost is the narration call in step 5, invoked once per detected finding — the number to calculate and cite in the demo/deck ("N findings/day × $X/call at SarvamAI/OpenAI's published per-token rate").
- **Latency:** the deterministic benchmarking/attribution path (steps 2–4) is a local SQL aggregation, sub-second; only the narration API call in step 5 carries meaningful (network-bound) latency, and it happens inside the background scheduled job, not on a user-facing request path.
- **Local-first reliability:** the only external network dependency in the entire system is the narration API call; the sense/reason/decide/act loop, the data layer, and the UI all function without internet access, which materially reduces demo-day risk compared to a design where the orchestrator itself lived in AWS.
- **Provider resilience:** the `NarrationClient` abstraction means a SarvamAI outage or exhausted credits mid-hackathon is a one-line config change to OpenAI, not a rebuild — worth actually rehearsing this switch once before the event so it's proven, not just designed.
- **Multi-tenancy:** the canonical schema still carries a `tenant_id`/`client_id` on core entities even though the demo only shows one tenant — cheap to state now, expensive to retrofit later, and specifically what the deployability bonus asks about.
- **Security/auth:** explicitly out of scope per the brief.

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Real sample dataset schema differs materially from Phase-0 assumptions | Canonical-model + adapter pattern (Section 3) isolates the blast radius to one mapping class |
| SarvamAI credits exhausted before/during the event | `NarrationClient` interface with a proven OpenAI fallback, switchable via one config value — test the switch in advance, not live |
| Narration API unreachable at demo time (venue wifi) | Cache/pre-generate a known-good narration for the planted-anomaly scenario as a fallback path so the live demo isn't fully dependent on a live API call; the deterministic reasoning still runs and displays regardless |
| LLM narration produces incorrect-sounding or overconfident text | Narration prompt is constrained to rephrase a structured finding, not to invent new numbers — the LLM never sees raw data, only the pre-computed result object |
| Demo alert never fires live | Retain a manual "run agent cycle now" trigger (an internal endpoint or admin button) rather than waiting on the schedule, plus keep the planted-anomaly data guaranteed to trigger at least one finding on any run |

## 8. Future production deployment (described, not built)

For the deployability bonus narrative, the same layer boundaries map directly onto AWS without a redesign:

- Custom Java orchestrator → **AWS Step Functions** state machine, same pipeline stages, triggered by **EventBridge**.
- PostgreSQL (Docker) → **Amazon RDS/Aurora PostgreSQL**, same schema.
- `NarrationClient` → an additional `BedrockNarrationClient` implementation of the same interface (no other code changes), or continue calling SarvamAI/OpenAI directly from AWS if preferred.
- Mocked actuators → **SES** (email) and **SNS** (push/webhook).
- Docker Compose → containers deployed on **ECS/Fargate**, with the Angular build served via **S3 + CloudFront**.
- Multi-tenancy: the `tenant_id` already in the schema becomes the basis for row-level isolation or per-tenant schema partitioning.

This is presented as a "same architecture, different runtime for each swappable interface" story — the point being that nothing about the local demo's design would need to change, only which implementation of each interface is wired in.
