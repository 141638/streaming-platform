/**
 * A room ban as returned by the chat-service moderation API.
 * Mirrors {@code com.streaming.chat.api.dto.BanResponse}.
 */
export interface BanResponseDto {
  readonly id: string;
  readonly roomId: string;
  readonly bannedSubject: string;
  readonly bannedBySubject: string;
  readonly reason: string | null;
  readonly createdAt: string;
  /** ISO-8601 expiry; {@code null} = permanent ban. */
  readonly expiresAt: string | null;
}
