import { ChannelStatsDto } from './channel-stats.dto';
import { SocialLinkDto } from './social-link.dto';
import { StreamSummaryResponseDto } from './stream-summary-response.dto';

/**
 * Safe cross-user channel projection from {@code GET /v1/channels/{username}}.
 * Any authenticated user may read any channel; owner-only fields
 * (broadcasterSubject, publish key, rtmpUrl) are excluded server-side.
 */
export interface ChannelResponseDto {
  readonly username: string;
  readonly verified: boolean | null;
  readonly sessions: readonly StreamSummaryResponseDto[];
  readonly recentCategories: readonly string[];
  /** The broadcaster's channel bio / description (from broadcaster_profile), or null if never set. */
  readonly bio: string | null;
  /** Social platform links from the profile, or empty array if none set. */
  readonly socialLinks: readonly SocialLinkDto[] | null;
  /** Derived channel statistics computed from existing sessions. */
  readonly stats: ChannelStatsDto;
}
