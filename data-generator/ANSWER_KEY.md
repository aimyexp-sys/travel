# Answer key — planted anomalies and injected messiness

Internal reference only — don't show this during the demo, it's what your agent should
independently discover and explain. Regenerating with `python3 generate_data.py` reproduces
this exact dataset (seed = 42).

## Planted stories (for the agent to find and narrate)

**1. Vendor OTA degradation — Vendor A (V1) and Vendor E (V5)**
Starting 2026-08-04 (last ~4 weeks of the dataset), both vendors' delay distribution shifts
worse, driven specifically by a jump in `driver_late`-coded delays. At a 10-minute OTA
threshold: Vendor A goes from ~81% → ~72%, Vendor E from ~83% → ~73%, and `driver_late` jumps
from a ~25% baseline share of delay reasons to ~37-38% for both vendors in that window. This is
the dataset's version of the brief's own example ("OTA was 85%, now 78%, SLA is 90%, two
vendors responsible for the gap") — pick your SLA threshold (e.g. 90% OTA within some delay
tolerance) so both vendors show a real, sustained breach.

**2. Recurring zone/shift delay + GPS-gap pattern — Marathahalli, Night Shift (S3)**
This specific zone+shift combination has an elevated `traffic`-reason delay bias and a much
higher GPS-trace gap rate (~41% of trips missing traces, vs. ~10% baseline elsewhere) for the
*entire* period, not just a recent window — representing a persistent, structural issue (e.g.
poor network coverage / congested route) rather than a trend.

**3. Cost anomaly — Vendor B (V2), last ~6 weeks**
Starting 2026-07-21, Vendor B's effective cost-per-km ramps up (roughly +13% on average over
the window, ramping from 1.0x to 1.35x of its base per-km rate by period end) with no
corresponding change in trip distance or delay/quality — a pure cost-per-unit creep, the kind
that's easy to miss in a monthly total but obvious once benchmarked per-km against other
vendors or against Vendor B's own trend.

**4. Safety incident cluster — driver D0007 (Vendor D), 2026-07-11 to 2026-07-24**
Driver D0007 accumulates 14 safety incidents in this 2-week window (vs. 1-2 for any other
driver in the whole 3-month dataset) — a clear single-driver cluster rather than
fleet-wide noise.

## Injected data-quality issues (for the "handles messy data gracefully" good-to-have)

- **Incomplete roster**: ~4% of rows in `employees.csv` have a null `shift_id` or `pickup_zone`.
- **Unmatched trip_employees**: ~2.5% of `trip_employees.csv` rows reference an `employee_id`
  (`E9xxx` range) that does not exist in `employees.csv` — simulates a roster-sync mismatch.
- **Duplicate trips**: ~0.5% of `trip_id`s appear twice in `trips.csv` — simulates an export
  duplication bug.
- **Missing timestamps**: `actual_pickup_time` is null on no-show trips plus a small random
  ~1.5% of otherwise-normal trips (unlogged events); `actual_drop_time` is null on any
  non-completed trip.
- **GPS gaps**: baseline ~8-10% of completed trips have zero GPS trace rows at all, plus the
  much higher rate at the Marathahalli/Night-Shift pattern above.

## Reproducing / regenerating

```
cd data-generator
python3 -m pip install --user pandas faker   # if not already installed
python3 generate_data.py
```

Output lands in `data/` (relative to wherever the script is run from) as the CSVs described in
`data/SCHEMA.md`. The seed is fixed, so output is identical across runs unless the script itself
is edited.
