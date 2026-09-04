export interface FleetMetric {
  metric: string;
  metricDisplayName: string;
  higherIsBetter: boolean;
  currentValue: number | null;
  trendDeltaAbsolute: number | null;
  slaThreshold: number | null;
  slaBreached: boolean | null;
}

export interface ProblemArea {
  title: string;
  narrative: string;
  status: string;
}

export interface LeadershipBrief {
  periodEnd: string;
  windowDays: number;
  executiveSummary: string;
  executiveSummaryProvider: string;
  fleetMetrics: FleetMetric[];
  topProblemAreas: ProblemArea[];
  wins: string[];
}
