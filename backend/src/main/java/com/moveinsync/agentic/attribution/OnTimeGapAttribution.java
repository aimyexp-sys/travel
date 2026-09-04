package com.moveinsync.agentic.attribution;

import java.time.LocalDate;
import java.util.List;

/** Fleet-wide OTA vs SLA/trend, decomposed to which vendors are responsible and why. */
public record OnTimeGapAttribution(
        LocalDate periodStart,
        LocalDate periodEnd,
        double fleetOtaValue,
        Double fleetOtaTrendAbsolute,
        double slaThreshold,
        boolean fleetSlaBreached,
        List<VendorGapContribution> topContributors  // sorted by gapContributionPercent desc
) {}
