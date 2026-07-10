/** Derived channel statistics computed from existing session data. */
export interface ChannelStatsDto {
  readonly totalStreams: number;
  readonly totalHoursStreamed: number;
  readonly topCategory: string | null;
}
