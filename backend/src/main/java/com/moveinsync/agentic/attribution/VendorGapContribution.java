package com.moveinsync.agentic.attribution;

import java.util.List;

/**
 * One vendor's share of a fleet-wide on-time shortfall, plus why (reason
 * code breakdown). gapContributionPercent is this vendor's late-trip count
 * as a percentage of ALL late trips fleet-wide - this is what "two vendors
 * are responsible for X% of the gap" means computed, not asserted.
 */
public record VendorGapContribution(
        String vendorId,
        double otaValue,
        Double otaTrendAbsolute,       // null if no prior-period data
        boolean slaBreached,
        int lateTripCount,
        double gapContributionPercent,
        List<ReasonCodeBreakdown> reasonCodeBreakdown,  // sorted desc by count
        String dominantReasonCode                        // null if no coded delays
) {}
