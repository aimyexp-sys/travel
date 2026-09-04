package com.moveinsync.agentic.attribution;

/** One delay_reason_code's share of a vendor's coded delays in a period. */
public record ReasonCodeBreakdown(String reasonCode, int count, double sharePercent) {}
