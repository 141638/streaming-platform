/**
 * Minimal identity projection from {@code GET /v1/channels/{username}/identity}.
 * Used by ChannelPage to render the channel header.
 */
export interface ChannelIdentityResponseDto {
  readonly username: string;
  readonly verified: boolean | null;
}
