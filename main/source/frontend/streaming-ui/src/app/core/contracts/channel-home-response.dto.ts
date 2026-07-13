import { StreamSummaryResponseDto } from './stream-summary-response.dto';

/**
 * Home tab projection from {@code GET /v1/channels/{username}/home}.
 * Used by HomeTabComponent to render the session rail and category strip.
 */
export interface ChannelHomeResponseDto {
  readonly sessions: readonly StreamSummaryResponseDto[];
  readonly recentCategories: readonly (string | null)[] | null;
}
