-- Canonical schema (Phase 2). Loaded by SyntheticSourceAdapter today, and by
-- SampleDatasetAdapter once the real MoveInSync sample file is available -
-- both map into these same tables, so nothing above this layer (Phase 3
-- onward) needs to know which source produced the data. See /data/SCHEMA.md
-- for the source CSV shapes this is loaded from.
--
-- Design note: dimension tables (shifts, vendors, drivers, routes,
-- employees) have real primary keys and FK constraints. Fact/event tables
-- (trips, trip_employees, gps_traces, delay_records, safety_incidents,
-- feedback, cost_records) deliberately do NOT enforce FK constraints on
-- trip_id/employee_id, and trips.trip_id is NOT unique - the sample dataset
-- has intentionally injected messiness (duplicate trip rows, trip_employees
-- rows referencing unknown employees) that ingestion must load and flag
-- rather than reject or crash on. See data_quality_issues below.

-- ---- dimension tables ----

CREATE TABLE shifts (
    shift_id        VARCHAR(16) PRIMARY KEY,
    shift_name      VARCHAR(64) NOT NULL,
    scheduled_start VARCHAR(8)  NOT NULL,
    scheduled_end   VARCHAR(8)  NOT NULL
);

CREATE TABLE vendors (
    vendor_id           VARCHAR(16) PRIMARY KEY,
    vendor_name          VARCHAR(128) NOT NULL,
    mode_types_served    VARCHAR(64) NOT NULL,
    contract_start       DATE
);

CREATE TABLE drivers (
    driver_id    VARCHAR(16) PRIMARY KEY,
    vendor_id    VARCHAR(16) NOT NULL REFERENCES vendors (vendor_id),
    driver_name  VARCHAR(128) NOT NULL,
    rating       NUMERIC(3,2)
);
CREATE INDEX idx_drivers_vendor ON drivers (vendor_id);

CREATE TABLE routes (
    route_id             VARCHAR(16) PRIMARY KEY,
    mode                 VARCHAR(16) NOT NULL,
    origin_zone          VARCHAR(64) NOT NULL,
    destination_zone     VARCHAR(64) NOT NULL,
    planned_distance_km  NUMERIC(6,2)
);

CREATE TABLE employees (
    employee_id  VARCHAR(16) PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    department   VARCHAR(64)  NOT NULL,
    shift_id     VARCHAR(16) REFERENCES shifts (shift_id),   -- nullable: incomplete roster
    pickup_zone  VARCHAR(64)                                  -- nullable: incomplete roster
);
CREATE INDEX idx_employees_shift ON employees (shift_id);
CREATE INDEX idx_employees_zone ON employees (pickup_zone);

-- ---- fact / event tables (permissive - see design note above) ----

CREATE TABLE trips (
    id                     BIGSERIAL PRIMARY KEY,
    trip_id                VARCHAR(16) NOT NULL,     -- not unique: duplicate rows are a planted data-quality issue
    route_id               VARCHAR(16),
    driver_id              VARCHAR(16),
    vendor_id              VARCHAR(16),
    shift_id               VARCHAR(16),
    scheduled_pickup_time  TIMESTAMP NOT NULL,
    actual_pickup_time     TIMESTAMP,                -- nullable: no-show / unlogged event
    scheduled_drop_time    TIMESTAMP,
    actual_drop_time       TIMESTAMP,                -- nullable: not completed
    distance_km            NUMERIC(6,2),
    cost                   NUMERIC(10,2),
    mode                   VARCHAR(16),
    status                 VARCHAR(16)
);
CREATE INDEX idx_trips_trip_id ON trips (trip_id);
CREATE INDEX idx_trips_vendor ON trips (vendor_id);
CREATE INDEX idx_trips_shift ON trips (shift_id);
CREATE INDEX idx_trips_scheduled_pickup ON trips (scheduled_pickup_time);
CREATE INDEX idx_trips_route ON trips (route_id);

CREATE TABLE trip_employees (
    id           BIGSERIAL PRIMARY KEY,
    trip_id      VARCHAR(16) NOT NULL,
    employee_id  VARCHAR(16) NOT NULL     -- NOT FK-enforced: some rows reference unknown employees (planted roster-sync issue)
);
CREATE INDEX idx_trip_employees_trip ON trip_employees (trip_id);
CREATE INDEX idx_trip_employees_employee ON trip_employees (employee_id);

CREATE TABLE gps_traces (
    id          BIGSERIAL PRIMARY KEY,
    trip_id     VARCHAR(16) NOT NULL,
    event_time  TIMESTAMP NOT NULL,     -- CSV column is "timestamp"; renamed to avoid the reserved-word footgun
    lat         NUMERIC(9,6),
    lon         NUMERIC(9,6),
    speed       NUMERIC(5,1)
);
CREATE INDEX idx_gps_traces_trip ON gps_traces (trip_id);

CREATE TABLE delay_records (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             VARCHAR(16) NOT NULL,
    delay_minutes       NUMERIC(6,1) NOT NULL,
    delay_reason_code   VARCHAR(32)          -- nullable when delay <= 2 min
);
CREATE INDEX idx_delay_records_trip ON delay_records (trip_id);

CREATE TABLE safety_incidents (
    id             BIGSERIAL PRIMARY KEY,
    incident_id    VARCHAR(16) NOT NULL,
    trip_id        VARCHAR(16) NOT NULL,
    incident_type  VARCHAR(32),
    severity       VARCHAR(16)
);
CREATE INDEX idx_safety_incidents_trip ON safety_incidents (trip_id);

CREATE TABLE feedback (
    id            BIGSERIAL PRIMARY KEY,
    trip_id       VARCHAR(16) NOT NULL,
    employee_id   VARCHAR(16) NOT NULL,
    rating        INTEGER,
    comment_text  TEXT
);
CREATE INDEX idx_feedback_trip ON feedback (trip_id);

CREATE TABLE cost_records (
    id             BIGSERIAL PRIMARY KEY,
    trip_id        VARCHAR(16) NOT NULL,
    base_fare      NUMERIC(8,2),
    surcharge      NUMERIC(8,2),
    total_cost     NUMERIC(10,2),
    billing_month  VARCHAR(7)
);
CREATE INDEX idx_cost_records_trip ON cost_records (trip_id);
CREATE INDEX idx_cost_records_month ON cost_records (billing_month);

-- ---- ingestion bookkeeping ----

CREATE TABLE ingestion_runs (
    id            BIGSERIAL PRIMARY KEY,
    source        VARCHAR(32) NOT NULL,     -- e.g. 'synthetic', 'sample'
    started_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(16) NOT NULL,     -- RUNNING | SUCCEEDED | FAILED
    rows_loaded   INTEGER,
    notes         TEXT
);

CREATE TABLE data_quality_issues (
    id             BIGSERIAL PRIMARY KEY,
    ingestion_run_id  BIGINT REFERENCES ingestion_runs (id),
    source_table   VARCHAR(32) NOT NULL,
    issue_type     VARCHAR(64) NOT NULL,
    issue_count    INTEGER NOT NULL,
    detail         VARCHAR(256),
    detected_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
