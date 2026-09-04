package com.moveinsync.agentic.orchestrator;

import com.moveinsync.agentic.attribution.AttributionService;
import com.moveinsync.agentic.attribution.OnTimeGapAttribution;
import com.moveinsync.agentic.attribution.VendorGapContribution;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scenario 1 from the build plan: a vendor sustaining an OTA SLA breach,
 * driven by a real trend decline (not a one-off blip) and material enough
 * to matter (a meaningful share of fleet-wide late trips) - reuses Phase
 * 4's AttributionService entirely, this class only adds the "is this worth
 * an action" thresholding on top of already-correct numbers.
 */
@Component
public class VendorSlaBreachDetector implements FindingDetector {

    private static final int WINDOW_DAYS = 28;
    private static final double SUSTAINED_DECLINE_THRESHOLD_POINTS = 3.0;
    private static final double MATERIAL_CONTRIBUTION_THRESHOLD_PERCENT = 15.0;

    private final AttributionService attributionService;

    public VendorSlaBreachDetector(AttributionService attributionService) {
        this.attributionService = attributionService;
    }

    @Override
    public List<Finding> detect(LocalDate asOf) {
        OnTimeGapAttribution attribution = attributionService.attributeOnTimeGap(WINDOW_DAYS, asOf);
        if (!attribution.fleetSlaBreached()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        for (VendorGapContribution v : attribution.topContributors()) {
            boolean sustainedDecline = v.otaTrendAbsolute() != null
                    && v.otaTrendAbsolute() <= -SUSTAINED_DECLINE_THRESHOLD_POINTS;
            boolean materialContributor = v.gapContributionPercent() >= MATERIAL_CONTRIBUTION_THRESHOLD_PERCENT;

            if (v.slaBreached() && sustainedDecline && materialContributor) {
                findings.add(new Finding(
                        FindingType.VENDOR_SLA_BREACH,
                        "vendor-sla:" + v.vendorId(),
                        v.vendorId(), null, null,
                        "Vendor " + v.vendorId() + " sustained on-time SLA breach",
                        buildFacts(attribution, v)
                ));
            }
        }
        return findings;
    }

    private String buildFacts(OnTimeGapAttribution a, VendorGapContribution v) {
        String reasonPart = v.dominantReasonCode() != null
                ? String.format(Locale.ROOT, ", dominant delay reason: %s (%.0f%% of its coded delays)",
                    v.dominantReasonCode(), v.reasonCodeBreakdown().get(0).sharePercent())
                : "";
        return String.format(Locale.ROOT,
                "Metric: On-Time Arrival Rate, Vendor %s%n" +
                "Period: %s to %s (%d days)%n" +
                "Vendor value: %.1f%% (SLA: %.0f%%, SLA BREACHED)%n" +
                "Trend vs prior period: %+.1f points - a sustained decline, not a one-off dip%n" +
                "Share of all fleet-wide late trips: %.0f%% (%d late trips)%s%n" +
                "Fleet-wide context: %.1f%% overall (SLA %.0f%%, %s)%n",
                v.vendorId(), a.periodStart(), a.periodEnd(), WINDOW_DAYS,
                v.otaValue(), a.slaThreshold(), v.otaTrendAbsolute(),
                v.gapContributionPercent(), v.lateTripCount(), reasonPart,
                a.fleetOtaValue(), a.slaThreshold(), a.fleetSlaBreached() ? "SLA BREACHED" : "within SLA");
    }
}
