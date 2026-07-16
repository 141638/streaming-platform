/**
 * Minimal identity projection from {@code GET /v1/channels/{username}/identity}.
 * Used by ChannelPage to render the channel header and drive the Follow button.
 */
export interface ChannelIdentityResponseDto {
  readonly username: string;
  readonly verified: boolean | null;
  /** The broadcaster's JWT {@code sub} — used as {@code targetId} for follow API calls. */
  readonly broadcasterSubject: string | null;
}
