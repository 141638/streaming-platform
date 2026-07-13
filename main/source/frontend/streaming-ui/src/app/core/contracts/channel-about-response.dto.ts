import { ChannelStatsDto } from './channel-stats.dto';
import { SocialLinkDto } from './social-link.dto';

/**
 * About tab projection from {@code GET /v1/channels/{username}/about}.
 * Used by AboutTabComponent to render bio, social links, and stats.
 */
export interface ChannelAboutResponseDto {
  /** The broadcaster's channel bio / description, or null if never set. */
  readonly bio: string | null;
  /** Social platform links from the profile, or empty array if none set. */
  readonly socialLinks: readonly SocialLinkDto[] | null;
  /** Derived channel statistics computed from all existing sessions. */
  readonly stats: ChannelStatsDto;
}
