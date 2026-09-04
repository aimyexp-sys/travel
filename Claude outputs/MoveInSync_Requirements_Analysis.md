# Requirements Analysis: Agentic Intelligence & Reporting Layer for Enterprise Mobility (MoveInSync Hackathon)

## 1. Restating the core ask

MoveInSync wants an **agentic layer**, not a dashboard. The distinction they're drawing repeatedly across the brief: a dashboard displays a number; an agent notices the number, figures out what it means, and does something about it. The three verbs in the brief — *senses, reasons, acts* — are the litmus test every feature should be checked against. If a feature only displays or only answers a question when asked, it's not agentic by their definition; it has to also initiate.

The recurring example they give is telling: "OTA is 78%" is a passive fact. "It was 85% last month, SLA is 90%, and two vendors are responsible for the gap" is the agentic version — trend + SLA + attribution, delivered without being asked. Every core feature should aim to produce sentences like the second one, not the first.

## 2. Who it's for (and what "serving" them means)

Three personas are named, and the mandatory requirements only ask you to serve **one** well — but the bonus criteria (leadership-ready, shareable output) point toward the strategic persona being the highest-leverage choice if you have to pick one.

| Persona | Time horizon | What "good" looks like for them | Native output format |
|---|---|---|---|
| **Transport manager** (operational) | Now / today | A signal they can act on immediately — a vendor to call, a shift to re-plan, an escalation to raise | Alert / notification, short and specific |
| **Transport & facilities head** (strategic) | Week / month / quarter | A cost-safety-experience narrative built for them, not by them, that survives being forwarded to leadership | Structured report / narrative brief |
| **Team / line manager** (shift-based) | Today's shift | Who made it, who didn't, how it affects floor readiness | Real-time shift roster / status view |

Picking a primary persona matters because it determines your default time grain (shift vs. day vs. month), your default tone (terse alert vs. narrative report), and your default channel (push/chat vs. email/PDF). Trying to serve all three with one undifferentiated output is a likely way to satisfy none of them well — the brief explicitly only requires one.

## 3. Functional requirements, decomposed

### 3.1 Sense (perception layer)
- Ingest the provided anonymised dataset: trip logs across cab, nodal, and shuttle modes.
- Component data types implied: GPS traces, delay/timeliness records, vendor performance records, cost data, employee feedback, (likely) driver and roster data.
- Must tolerate messy/incomplete data — GPS gaps, unmatched records, incomplete rosters — gracefully (good-to-have, but "handles gracefully" implies the system shouldn't crash or silently produce wrong numbers; at minimum it should flag data quality issues rather than hide them).

### 3.2 Reason (the actual hard part)
This is where the evaluation weight concentrates (Business impact 35 + Agentic design 20 = 55/100 depend on this layer being real, not decorative). Reasoning has two mandatory sub-requirements:

1. **Benchmarking/contextualization** — every surfaced metric must be compared against at least one reference point:
   - Historical trend (this month vs. last month/quarter)
   - SLA / goal (actual vs. committed threshold)
   - Industry benchmark (actual vs. external norm)
   - Peer comparison (this vendor/site/shift vs. others in the same dataset)
2. **Attribution** — the OTA example doesn't stop at "gap exists," it names *which two vendors* are responsible. Reasoning should decompose an aggregate metric down to root cause/contributor, not just flag the aggregate moved.

Anomaly/insight detection (Section 7) is really a sensing+reasoning combination: statistically or rule-detecting an outlier, then explaining why it matters relative to the benchmark.

### 3.3 Act (the differentiator)
"Acts — with minimal human prompting" is mandatory language. Passive Q&A alone ("ask the data a question, get an answer") explicitly does **not** satisfy the mandatory agentic-behaviour requirement on its own — it's listed as one of six possible *output forms*, not as sufficient by itself ("not a passive dashboard or query-only tool"). Acting can mean:
- Proactive alerting/triggers fired on a schedule or condition, unprompted
- Automated report/narrative generation, delivered on a cadence
- Automated communications (e.g., drafting/sending the vendor escalation, the leadership summary)

The good-to-have "proactive triggers rather than purely on-demand" reinforces that a chat-only interface, however smart, is the weaker submission; something needs to run without a user opening the app.

### 3.4 Combine forms (good-to-have, but cheap leverage)
Two or more of the six output forms combined is explicitly rewarded. The natural low-effort combo given the mandatory pieces above: **anomaly detection → proactive alert → automated narrative**, optionally with a **conversational agent** layered on top for follow-up drill-down ("why did OTA drop") using the same reasoning engine. That's 3–4 of the six forms from one underlying pipeline.

## 4. Non-functional / evaluation-driving requirements

These aren't listed as "requirements" in Section 8 but are graded directly, so they function as requirements in practice.

- **Cost & latency at enterprise scale (20% weight)** — the brief explicitly wants evidence you thought about inference cost per interaction and efficiency at volume, not just that a model call works once in a demo. Implies: caching/precomputation of routine metrics rather than re-running an LLM over raw trip logs on every request; using an LLM only for reasoning/narration over pre-aggregated stats, not for arithmetic; citing approximate cost/latency numbers is worth more than staying silent on it.
- **Architecture & code quality, deployability (20% weight)** — sound structure, and a credible story for how this plugs into an existing mobility platform (multi-tenancy, latency, cost — repeated from criterion 2). A clean separation between data layer / reasoning layer / delivery layer will read well here.
- **Leadership-ready, forward-without-rework output (bonus, feeds into 35% business-impact)** — at least one output artifact should be presentable as-is: correct tone, no exposed jargon or debug text, formatted like something a human would have written.
- **Functionality (25%)** — end-to-end, demo-able, on the actual provided dataset. This is a strong signal to build one complete vertical slice well rather than five shallow features.

## 5. Explicit non-goals (don't spend time here)

- No production-grade auth/security
- No full historical data pipeline (the sample dataset is the only data — no need to build ingestion for a live system)
- No integration with real vendor systems
- No live system access — everything is against the static anonymised sample

Time spent on any of the above is very likely time taken away from the 55%-weighted business-impact + agentic-design criteria.

## 6. Reference-point sourcing — the one ambiguous mandatory requirement

The mandatory contextualization requirement allows historical trend, SLA/goal, industry benchmark, *or* peer comparison — participant's choice of which. Practically:
- **Historical trend** and **peer comparison** are derivable entirely from the provided dataset (e.g., this week vs. last week; this vendor vs. other vendors in the same file) — lowest risk, no external assumptions.
- **SLA/goal** requires either a value present in the dataset or a stated assumption (e.g., "OTA SLA = 90%" invented for the demo) — fine, but should be clearly flagged as an assumed constant, not implied to be real MoveInSync policy.
- **Industry benchmark** requires an external number not in the dataset — highest risk of being unverifiable/fabricated-looking; if used, cite it as illustrative.

Given the dataset is the only guaranteed input, building trend + peer comparison as the backbone (both computable, both defensible) and layering SLA-threshold checks on top (with assumptions stated) is the lowest-risk way to satisfy this mandatory item convincingly.

## 7. Suggested MVP scope (mapping directly to mandatory + highest-value good-to-haves)

1. **Data layer**: load and clean the sample dataset; explicit handling/flagging of gaps (missing GPS, unmatched trip↔driver↔employee records) rather than silent drops.
2. **Metrics + benchmarking engine**: compute core KPIs (OTA, delay minutes, cost/trip, safety incidents, vendor-level breakdowns) with trend-over-time and peer/vendor comparison built in from the start, not bolted on.
3. **Reasoning/attribution layer**: when a metric breaches a threshold or trend, decompose it to contributing vendor/route/shift — this is what turns a number into the "78% vs 85% vs SLA 90%, two vendors responsible" sentence.
4. **Act — proactive layer**: scheduled/triggered scan of the data that autonomously generates and "sends" (or queues) an alert or narrative when something crosses a threshold, without a user asking.
5. **One persona-appropriate output surface**: e.g., a strategic-head narrative brief (bonus: forward-to-leadership-ready) or an operational-manager alert feed — pick one as primary, per Section 2.
6. **Optional layer**: conversational drill-down on top of the same reasoning engine, for the "combine two+ output forms" good-to-have.

## 8. Deliverables checklist (Section 10, easy to lose track of under time pressure)

- Source code repo (GitHub/GitLab)
- Architecture diagram
- README + setup instructions
- Sample inputs/outputs
- Presentation deck
- Live demo
- Demo video — only if requested, don't over-invest here by default

## 9. Risks / things likely to cost points if missed

- Building a capable Q&A chatbot but no autonomous trigger → fails the "not passive/query-only" mandatory clause even if impressive.
- Surfacing metrics without any of the four benchmark types → fails a second mandatory clause even with good UI.
- An LLM call on every raw-data query with no precomputation/caching story → weak on the 20%-weighted cost/latency criterion regardless of demo polish.
- Output that reads like a debug log or raw JSON rather than something a facilities head could forward → forfeits the explicit bonus criterion and dents the 35%-weighted business-impact score.
