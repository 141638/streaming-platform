export interface WatchHistoryEntryDto {
  readonly id: string;
  readonly streamId: string;
  readonly title: string;
  readonly status: string;
  readonly category: string | null;
  readonly thumbnailUrl: string | null;
  readonly broadcasterUsername: string | null;
  readonly views: number;
  readonly watchedAt: string;
  readonly watchDurationSeconds: number;
}
