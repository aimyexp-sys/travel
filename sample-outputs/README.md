# Sample outputs

Real responses captured live from the running Docker Compose stack during Phase 4-6
verification (2026-09-04), against the seeded synthetic dataset (`data/`, seed=42). Nothing
here is fabricated or hand-written to look good - every number and every narrated sentence
came out of a genuine `curl`/browser request against the actual backend, including a live
SarvamAI (`sarvam-105b-conversations`) round trip. See `data-generator/ANSWER_KEY.md` for
what was deliberately planted in the dataset for the agent to find.

| File | What it shows | Planted story it surfaces |
|---|---|---|
| `01-on-time-gap-attribution.json` | `GET /api/insights/on-time-gap` - the brief's own worked example (OTA vs SLA/trend, decomposed by vendor and delay reason), fully computed and narrated | Vendor A (V1) and Vendor E (V5) OTA degradation, both driven by `driver_late` |
| `02-agent-actions-audit-trail.json` | `GET /api/agent/actions` - the full agent audit trail after two cycles | All three planted stories at once, each with the decision tier `DecisionPolicy` assigned: vendor breaches held for approval, the zone/shift pattern auto-fired, the cost anomaly logged for internal review |
| `03-run-cycle-with-dedup.json` | `POST /api/agent/run-cycle`, called twice in a row | Proves the dedup logic: an immediate re-run finds the same 2 findings again but creates 0 new actions (`actionsDeduped: 2`) - the agent doesn't re-flag the same issue every cycle |
| `04-leadership-brief-executive-summary.txt` | `GET /api/persona/leadership-brief`'s narrated executive summary, captured from two browser tabs independently | The fleet-wide KPI snapshot in one leadership-ready paragraph |

## Not included here (test live during judging instead)

- **Chat drill-down** (`POST /api/chat`) - ask it live; see the README's Phase 7 verification
  section for suggested questions ("why did on-time arrival drop this week?", "how is Vendor A
  doing on cost?", "what is happening in Marathahalli?").
- **Live WebSocket push** - best shown live, not as a static JSON blob: open the dashboard in
  two browser tabs, click "Run agent cycle now" in one, and watch the other tab's Agent
  activity feed update with no refresh.
- **Approve / dismiss** - clicking "Approve & send" on a `PENDING_APPROVAL` card logs a mocked
  "would have sent this" line in the backend logs (`docker-compose logs backend`) - a good
  moment to point at during a live demo.
