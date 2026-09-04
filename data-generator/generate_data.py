#!/usr/bin/env python3
"""
MoveInSync hackathon - synthetic dataset generator (Phase 0)

Generates a relational set of CSVs simulating enterprise mobility trip logs
(cab / nodal / shuttle), with:
  - ~3 months of weekday trip data
  - deliberately planted "stories" for the agent to discover (see ANSWER_KEY.md)
  - deliberately injected messiness (GPS gaps, unmatched records, missing
    timestamps, incomplete rosters, duplicate rows)

Reproducible: fixed random seed. Re-running produces identical output.
"""

import os
import random
from datetime import datetime, timedelta, time

import numpy as np
import pandas as pd
from faker import Faker

# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
fake = Faker()
Faker.seed(SEED)

OUT_DIR = os.environ.get("OUT_DIR", "data")
os.makedirs(OUT_DIR, exist_ok=True)

DATE_START = datetime(2026, 6, 1)
DATE_END = datetime(2026, 8, 31)
ALL_DATES = pd.date_range(DATE_START, DATE_END, freq="D")
WEEKDAYS = [d for d in ALL_DATES if d.weekday() < 5]  # Mon-Fri only
TOTAL_DAYS = (DATE_END - DATE_START).days + 1
DEGRADE_WINDOW_START = DATE_END - timedelta(days=27)   # last ~4 weeks
COST_ANOMALY_WINDOW_START = DATE_END - timedelta(days=41)  # last ~6 weeks
SAFETY_CLUSTER_START = DATE_START + timedelta(days=40)
SAFETY_CLUSTER_END = SAFETY_CLUSTER_START + timedelta(days=13)  # 2-week cluster

N_EMPLOYEES = 800
N_DRIVERS = 120

DEPARTMENTS = ["Engineering", "Operations", "Customer Support", "Finance", "HR", "Sales"]

ZONES = {
    # zone_name: (base_lat, base_lon, primary_mode, base_distance_km)
    "Whitefield":         (12.9698, 77.7500, "shuttle", 22),
    "Electronic City":    (12.8452, 77.6602, "shuttle", 19),
    "Koramangala":        (12.9352, 77.6245, "nodal", 9),
    "Indiranagar":        (12.9719, 77.6412, "nodal", 8),
    "HSR Layout":         (12.9121, 77.6446, "cab", 11),
    "Marathahalli":       (12.9569, 77.7011, "shuttle", 15),
    "BTM Layout":         (12.9166, 77.6101, "nodal", 10),
    "Hebbal":             (13.0355, 77.5970, "cab", 17),
}
ZONE_NAMES = list(ZONES.keys())
MODES = ["cab", "nodal", "shuttle"]
MODE_CAPACITY = {"cab": 4, "nodal": 8, "shuttle": 18}

SHIFTS = [
    {"shift_id": "S1", "shift_name": "Day Shift", "start": time(9, 0), "end": time(18, 0), "weight": 0.5},
    {"shift_id": "S2", "shift_name": "General Shift", "start": time(11, 0), "end": time(20, 0), "weight": 0.3},
    {"shift_id": "S3", "shift_name": "Night Shift", "start": time(22, 0), "end": time(7, 0), "weight": 0.2},
]
SHIFT_IDS = [s["shift_id"] for s in SHIFTS]
SHIFT_WEIGHTS = [s["weight"] for s in SHIFTS]

VENDORS = [
    {"vendor_id": "V1", "vendor_name": "Vendor A", "modes": ["cab", "nodal"]},
    {"vendor_id": "V2", "vendor_name": "Vendor B", "modes": ["nodal", "shuttle"]},
    {"vendor_id": "V3", "vendor_name": "Vendor C", "modes": ["cab"]},
    {"vendor_id": "V4", "vendor_name": "Vendor D", "modes": ["shuttle"]},
    {"vendor_id": "V5", "vendor_name": "Vendor E", "modes": ["cab", "shuttle"]},
]
VENDOR_BY_ID = {v["vendor_id"]: v for v in VENDORS}
VENDORS_FOR_MODE = {m: [v["vendor_id"] for v in VENDORS if m in v["modes"]] for m in MODES}

# Planted story parameters -------------------------------------------------
DEGRADING_VENDOR_IDS = ["V1", "V5"]          # Vendor A, Vendor E: OTA degrades in last ~4 weeks
PATTERN_ZONE = "Marathahalli"                # recurring GPS-gap / delay pattern
PATTERN_SHIFT = "S3"                         # ...specifically on the Night Shift
COST_ANOMALY_VENDOR_ID = "V2"                # Vendor B: cost/km creeps up, last ~6 weeks
SAFETY_DRIVER_VENDOR_ID = "V4"               # Vendor D driver with a clustered incident spell

REASON_CODES = ["traffic", "driver_late", "vehicle_issue", "route_change", "unknown"]
BASE_REASON_WEIGHTS = [0.40, 0.25, 0.15, 0.10, 0.10]

VENDOR_DELAY_MEAN = {"V1": 6.0, "V2": 5.0, "V3": 4.0, "V4": 7.0, "V5": 5.5}

BASE_FARE = {"cab": 80, "nodal": 40, "shuttle": 15}
PER_KM_RATE = {"cab": 14, "nodal": 9, "shuttle": 5}

print(f"Generating data for {len(WEEKDAYS)} weekdays "
      f"({DATE_START.date()} to {DATE_END.date()})...")

# --------------------------------------------------------------------------
# Dimension tables
# --------------------------------------------------------------------------
shifts_df = pd.DataFrame([
    {"shift_id": s["shift_id"], "shift_name": s["shift_name"],
     "scheduled_start": s["start"].strftime("%H:%M"), "scheduled_end": s["end"].strftime("%H:%M")}
    for s in SHIFTS
])

vendors_df = pd.DataFrame([
    {"vendor_id": v["vendor_id"], "vendor_name": v["vendor_name"],
     "mode_types_served": "|".join(v["modes"]),
     "contract_start": (DATE_START - timedelta(days=random.randint(200, 900))).date().isoformat()}
    for v in VENDORS
])

drivers = []
for i in range(1, N_DRIVERS + 1):
    driver_id = f"D{i:04d}"
    vendor_id = random.choice([v["vendor_id"] for v in VENDORS])
    drivers.append({
        "driver_id": driver_id,
        "vendor_id": vendor_id,
        "driver_name": fake.name(),
        "rating": round(float(np.clip(np.random.normal(4.2, 0.35), 2.5, 5.0)), 2),
    })
drivers_df = pd.DataFrame(drivers)
DRIVERS_BY_VENDOR = drivers_df.groupby("vendor_id")["driver_id"].apply(list).to_dict()
# Guarantee at least one driver from Vendor D exists for the safety-cluster story
SAFETY_DRIVER_ID = DRIVERS_BY_VENDOR[SAFETY_DRIVER_VENDOR_ID][0]

employees = []
for i in range(1, N_EMPLOYEES + 1):
    employee_id = f"E{i:05d}"
    shift_id = np.random.choice(SHIFT_IDS, p=SHIFT_WEIGHTS)
    zone = random.choice(ZONE_NAMES)
    employees.append({
        "employee_id": employee_id,
        "name": fake.name(),
        "department": random.choice(DEPARTMENTS),
        "shift_id": shift_id,
        "pickup_zone": zone,
    })
employees_df = pd.DataFrame(employees)

# Messiness: ~4% of employees have an incomplete roster assignment
# (missing shift or zone) -> they simply generate no trips, simulating
# real-world incomplete roster records.
incomplete_idx = employees_df.sample(frac=0.04, random_state=SEED).index
for idx in incomplete_idx:
    if random.random() < 0.5:
        employees_df.loc[idx, "shift_id"] = None
    else:
        employees_df.loc[idx, "pickup_zone"] = None

EMP_GROUPS = (
    employees_df.dropna(subset=["shift_id", "pickup_zone"])
    .groupby(["pickup_zone", "shift_id"])["employee_id"]
    .apply(list)
    .to_dict()
)

routes = []
route_lookup = {}
rid = 1
for zone, (lat, lon, primary_mode, base_dist) in ZONES.items():
    for mode in MODES:
        route_id = f"R{rid:03d}"
        dist = base_dist * (1.0 if mode == primary_mode else random.uniform(0.9, 1.15))
        routes.append({
            "route_id": route_id, "mode": mode, "origin_zone": zone,
            "destination_zone": "Office Campus", "planned_distance_km": round(dist, 1),
        })
        route_lookup[(zone, mode)] = route_id
        rid += 1
routes_df = pd.DataFrame(routes)

# --------------------------------------------------------------------------
# Fact tables: trips, trip_employees, gps_traces, delay_records,
#              safety_incidents, feedback, cost_records
# --------------------------------------------------------------------------
trips, trip_employees, gps_traces = [], [], []
delay_records, safety_incidents, feedback, cost_records = [], [], [], []

trip_counter = 1
gps_counter = 1
incident_counter = 1

def pick_reason_code(vendor_id, zone, shift_id, current_date):
    weights = np.array(BASE_REASON_WEIGHTS, dtype=float)
    # Planted trend: two vendors' OTA degrades in the last ~4 weeks,
    # driven specifically by "driver_late"
    if vendor_id in DEGRADING_VENDOR_IDS and current_date >= DEGRADE_WINDOW_START:
        weights[REASON_CODES.index("driver_late")] += 0.45
    # Planted recurring pattern: one zone/shift combo has a persistent
    # traffic-driven delay pattern for the whole period
    if zone == PATTERN_ZONE and shift_id == PATTERN_SHIFT:
        weights[REASON_CODES.index("traffic")] += 0.35
    weights = weights / weights.sum()
    return np.random.choice(REASON_CODES, p=weights)


for current_date in WEEKDAYS:
    for shift in SHIFTS:
        shift_id = shift["shift_id"]
        for zone in ZONE_NAMES:
            key = (zone, shift_id)
            candidates = EMP_GROUPS.get(key, [])
            if not candidates:
                continue
            # daily attendance ~92%
            attending = [e for e in candidates if random.random() < 0.92]
            if not attending:
                continue
            random.shuffle(attending)

            primary_mode = ZONES[zone][2]
            cap = MODE_CAPACITY[primary_mode]
            chunks = [attending[i:i + cap] for i in range(0, len(attending), cap)]

            for chunk in chunks:
                mode = primary_mode
                route_id = route_lookup[(zone, mode)]
                planned_dist = routes_df.loc[routes_df.route_id == route_id, "planned_distance_km"].iloc[0]

                vendor_pool = VENDORS_FOR_MODE[mode]
                vendor_id = random.choice(vendor_pool)
                # Force extra representation for the safety-cluster driver during
                # their incident window so the cluster is clearly visible in the data.
                if (vendor_id == SAFETY_DRIVER_VENDOR_ID
                        and SAFETY_CLUSTER_START <= current_date <= SAFETY_CLUSTER_END
                        and random.random() < 0.55):
                    driver_id = SAFETY_DRIVER_ID
                else:
                    driver_id = random.choice(DRIVERS_BY_VENDOR[vendor_id])

                sched_pickup_dt = datetime.combine(current_date, shift["start"]) - timedelta(minutes=45)
                sched_drop_dt = datetime.combine(current_date, shift["start"])

                # --- delay generation ---
                # Tuned so the planted OTA drop reads like the brief's own example
                # ("78% down from 85%, SLA 90%") rather than a dataset collapse.
                base_mean = VENDOR_DELAY_MEAN[vendor_id]
                zone_pattern_boost = 2.0 if (zone == PATTERN_ZONE and shift_id == PATTERN_SHIFT) else 0.0
                vendor_trend_boost = 0.0
                if vendor_id in DEGRADING_VENDOR_IDS and current_date >= DEGRADE_WINDOW_START:
                    vendor_trend_boost = 2.2
                delay_minutes = max(0, np.random.exponential(base_mean + zone_pattern_boost + vendor_trend_boost))
                delay_minutes = round(float(delay_minutes), 1)

                actual_pickup_dt = sched_pickup_dt + timedelta(minutes=delay_minutes)
                actual_drop_dt = sched_drop_dt + timedelta(minutes=max(0, delay_minutes - random.uniform(0, 5)))

                # --- status ---
                status_roll = random.random()
                status = "completed" if status_roll < 0.97 else ("no_show" if status_roll < 0.99 else "cancelled")

                # --- distance / cost, with a planted cost anomaly for one vendor ---
                distance_km = round(float(planned_dist * random.uniform(0.95, 1.08)), 1)
                per_km_rate = PER_KM_RATE[mode]
                if vendor_id == COST_ANOMALY_VENDOR_ID and current_date >= COST_ANOMALY_WINDOW_START:
                    days_in = (current_date - COST_ANOMALY_WINDOW_START).days
                    ramp = 1.0 + min(0.35, 0.35 * days_in / 42.0)  # ramps 1.0x -> 1.35x
                    per_km_rate = per_km_rate * ramp
                base_fare = BASE_FARE[mode]
                surcharge = round(random.uniform(0, 20) + (15 if random.random() < 0.05 else 0), 1)
                total_cost = round(base_fare + per_km_rate * distance_km + surcharge, 1)

                trip_id = f"T{trip_counter:06d}"
                trip_counter += 1

                trips.append({
                    "trip_id": trip_id, "route_id": route_id, "driver_id": driver_id,
                    "vendor_id": vendor_id, "shift_id": shift_id,
                    "scheduled_pickup_time": sched_pickup_dt.isoformat(),
                    "actual_pickup_time": None if (status == "no_show" or random.random() < 0.015) else actual_pickup_dt.isoformat(),
                    "scheduled_drop_time": sched_drop_dt.isoformat(),
                    "actual_drop_time": None if status != "completed" else actual_drop_dt.isoformat(),
                    "distance_km": distance_km, "cost": total_cost, "mode": mode, "status": status,
                })

                for emp_id in chunk:
                    trip_employees.append({"trip_id": trip_id, "employee_id": emp_id})

                # --- gps traces (with planted + baseline gaps) ---
                gap_prob = 0.08
                if zone == PATTERN_ZONE and shift_id == PATTERN_SHIFT:
                    gap_prob = 0.40
                if status == "completed" and random.random() > gap_prob:
                    base_lat, base_lon = ZONES[zone][0], ZONES[zone][1]
                    n_points = random.randint(5, 9)
                    for p in range(n_points):
                        frac = p / max(1, n_points - 1)
                        ts = sched_pickup_dt + timedelta(minutes=frac * 40)
                        gps_traces.append({
                            "trip_id": trip_id, "timestamp": ts.isoformat(),
                            "lat": round(base_lat + np.random.normal(0, 0.01) * (1 - frac), 6),
                            "lon": round(base_lon + np.random.normal(0, 0.01) * (1 - frac), 6),
                            "speed": round(float(np.clip(np.random.normal(28, 10), 3, 60)), 1),
                        })
                        gps_counter += 1

                # --- delay record ---
                reason_code = None if delay_minutes <= 2 else pick_reason_code(vendor_id, zone, shift_id, current_date)
                delay_records.append({
                    "trip_id": trip_id, "delay_minutes": delay_minutes, "delay_reason_code": reason_code,
                })

                # --- safety incidents (baseline rare, clustered for one driver) ---
                incident_prob = 0.006
                if driver_id == SAFETY_DRIVER_ID and SAFETY_CLUSTER_START <= current_date <= SAFETY_CLUSTER_END:
                    incident_prob = 0.35
                if random.random() < incident_prob:
                    safety_incidents.append({
                        "incident_id": f"I{incident_counter:05d}", "trip_id": trip_id,
                        "incident_type": random.choice(["harsh_braking", "speeding", "near_miss", "minor_collision"]),
                        "severity": random.choice(["low", "medium", "high"]),
                    })
                    incident_counter += 1

                # --- feedback (subset of riders) ---
                for emp_id in chunk:
                    if random.random() < 0.30:
                        rating_base = 5 - (delay_minutes / 15.0)
                        rating = int(np.clip(round(rating_base + np.random.normal(0, 0.6)), 1, 5))
                        comment = (
                            random.choice([
                                "Driver was late, missed my shift start.",
                                "Smooth ride, no complaints.",
                                "Vehicle was in poor condition.",
                                "On time and courteous driver.",
                                "Route felt longer than usual today.",
                                "Great experience overall.",
                            ]) if random.random() < 0.6 else fake.sentence(nb_words=10)
                        )
                        feedback.append({
                            "trip_id": trip_id, "employee_id": emp_id, "rating": rating, "comment_text": comment,
                        })

                cost_records.append({
                    "trip_id": trip_id, "base_fare": base_fare,
                    "surcharge": surcharge, "total_cost": total_cost,
                    "billing_month": current_date.strftime("%Y-%m"),
                })

print(f"  trips: {len(trips)}")

trips_df = pd.DataFrame(trips)
trip_employees_df = pd.DataFrame(trip_employees)
gps_traces_df = pd.DataFrame(gps_traces)
delay_records_df = pd.DataFrame(delay_records)
safety_incidents_df = pd.DataFrame(safety_incidents)
feedback_df = pd.DataFrame(feedback)
cost_records_df = pd.DataFrame(cost_records)

# --------------------------------------------------------------------------
# Additional messiness injection
# --------------------------------------------------------------------------
# 1) Unmatched trip_employees: ~2.5% reference an employee_id that doesn't
#    exist in the employees table (simulates a roster sync mismatch).
n_unmatched = int(len(trip_employees_df) * 0.025)
unmatched_idx = trip_employees_df.sample(n=n_unmatched, random_state=SEED).index
trip_employees_df.loc[unmatched_idx, "employee_id"] = [
    f"E9{random.randint(9000, 9999)}" for _ in range(n_unmatched)
]

# 2) Duplicate trip rows: ~0.5% of trips appear twice (simulates an
#    upstream export duplication bug).
dup_sample = trips_df.sample(frac=0.005, random_state=SEED)
trips_df = pd.concat([trips_df, dup_sample], ignore_index=True)

print("Writing CSVs...")
trips_df.to_csv(f"{OUT_DIR}/trips.csv", index=False)
employees_df.to_csv(f"{OUT_DIR}/employees.csv", index=False)
shifts_df.to_csv(f"{OUT_DIR}/shifts.csv", index=False)
vendors_df.to_csv(f"{OUT_DIR}/vendors.csv", index=False)
drivers_df.to_csv(f"{OUT_DIR}/drivers.csv", index=False)
routes_df.to_csv(f"{OUT_DIR}/routes.csv", index=False)
trip_employees_df.to_csv(f"{OUT_DIR}/trip_employees.csv", index=False)
gps_traces_df.to_csv(f"{OUT_DIR}/gps_traces.csv", index=False)
delay_records_df.to_csv(f"{OUT_DIR}/delay_records.csv", index=False)
safety_incidents_df.to_csv(f"{OUT_DIR}/safety_incidents.csv", index=False)
feedback_df.to_csv(f"{OUT_DIR}/feedback.csv", index=False)
cost_records_df.to_csv(f"{OUT_DIR}/cost_records.csv", index=False)

print("Done. Row counts:")
for name, df in [
    ("employees", employees_df), ("shifts", shifts_df), ("vendors", vendors_df),
    ("drivers", drivers_df), ("routes", routes_df), ("trips", trips_df),
    ("trip_employees", trip_employees_df), ("gps_traces", gps_traces_df),
    ("delay_records", delay_records_df), ("safety_incidents", safety_incidents_df),
    ("feedback", feedback_df), ("cost_records", cost_records_df),
]:
    print(f"  {name:16s} {len(df):>7d} rows")
