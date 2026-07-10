export interface CategoryCountDto {
  readonly category: string;
  readonly count: number;
}

/** Derived channel statistics computed from existing session data. */
export interface ChannelStatsDto {
  readonly totalStreams: number;
  readonly totalHoursStreamed: number;
  readonly topCategory: string | null;
  /** ISO-8601 timestamp of the oldest session, or null if no sessions. */
  readonly firstStreamedAt: string | null;
  /** Per-category stream counts, sorted by count descending. */
  readonly categoryBreakdown: readonly CategoryCountDto[] | null;
}
