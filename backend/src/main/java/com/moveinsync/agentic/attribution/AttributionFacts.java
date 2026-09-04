package com.moveinsync.agentic.attribution;

import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Deterministically renders an OnTimeGapAttribution into plain text - this,
 * not raw trip rows, is all any narration call ever sees. Shared between
 * InsightsController's dedicated worked-example endpoint and ChatService's
 * conversational drill-down (Phase 7), so both surfaces stay consistent
 * with a single source of truth instead of two hand-copied templates.
 */
public final class AttributionFacts {

    private AttributionFacts() {}

    public static String format(OnTimeGapAttribution a) {
        StringBuilder sb = new StringBuilder();
        int windowDays = (int) ChronoUnit.DAYS.between(a.periodStart(), a.periodEnd());

        sb.append(String.format(Locale.ROOT,
                "Metric: On-Time Arrival Rate%n" +
                "Period: %s to %s (%d days)%n" +
                "Fleet value: %.1f%% (SLA: %.0f%%, %s)%n",
                a.periodStart(), a.periodEnd(), windowDays,
                a.fleetOtaValue(), a.slaThreshold(),
                a.fleetSlaBreached() ? "SLA BREACHED" : "within SLA"));

        if (a.fleetOtaTrendAbsolute() != null) {
            sb.append(String.format(Locale.ROOT,
                    "Fleet trend vs prior period: %+.1f points%n", a.fleetOtaTrendAbsolute()));
        }

        sb.append("Vendor breakdown (sorted by share of fleet-wide late trips):\n");
        for (VendorGapContribution v : a.topContributors()) {
            String trendPart = v.otaTrendAbsolute() != null
                    ? String.format(Locale.ROOT, " (%+.1f pts vs prior period)", v.otaTrendAbsolute())
                    : "";
            String reasonPart = v.dominantReasonCode() != null
                    ? String.format(Locale.ROOT, ", dominant delay reason: %s (%.0f%% of its coded delays)",
                        v.dominantReasonCode(), v.reasonCodeBreakdown().get(0).sharePercent())
                    : "";
            sb.append(String.format(Locale.ROOT,
                    "- Vendor %s: OTA %.1f%%%s, %s, %.0f%% of all late trips fleet-wide (%d late trips)%s%n",
                    v.vendorId(), v.otaValue(), trendPart,
                    v.slaBreached() ? "SLA breached" : "within SLA",
                    v.gapContributionPercent(), v.lateTripCount(), reasonPart));
        }
        return sb.toString();
    }
}
