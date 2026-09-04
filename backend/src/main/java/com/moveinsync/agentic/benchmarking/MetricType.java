package com.moveinsync.agentic.benchmarking;

/**
 * The six core KPIs from the brief's problem definition (ops scale,
 * timeliness/delays, safety/compliance, cost, employee experience). Each
 * carries its own SLA threshold (nullable - not every metric has one) and
 * direction, so BenchmarkingService can compute breach/trend generically
 * without per-metric special-casing at the call site.
 */
public enum MetricType {
    ON_TIME_ARRIVAL_RATE("on_time_arrival_rate", "On-Time Arrival Rate (%)", true, 90.0),
    AVERAGE_DELAY_MINUTES("average_delay_minutes", "Average Delay (minutes)", false, 10.0),
    COST_PER_KM("cost_per_km", "Cost per Km", false, null),
    COST_PER_TRIP("cost_per_trip", "Cost per Trip", false, null),
    SAFETY_INCIDENT_RATE("safety_incident_rate", "Safety Incidents per 100 Trips", false, 1.0),
    FEEDBACK_SCORE("feedback_score", "Rider Feedback Score (1-5)", true, 4.0);

    private final String key;
    private final String displayName;
    private final boolean higherIsBetter;
    private final Double slaThreshold;

    MetricType(String key, String displayName, boolean higherIsBetter, Double slaThreshold) {
        this.key = key;
        this.displayName = displayName;
        this.higherIsBetter = higherIsBetter;
        this.slaThreshold = slaThreshold;
    }

    public String key() { return key; }
    public String displayName() { return displayName; }
    public boolean higherIsBetter() { return higherIsBetter; }
    public Double slaThreshold() { return slaThreshold; }
}
