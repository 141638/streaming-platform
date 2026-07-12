/**
 * A room ban as returned by the chat-service moderation API.
 * Mirrors {@code com.streaming.chat.api.dto.BanResponse}.
 */
export interface BanResponseDto {
  readonly id: string;
  readonly roomId: string;
  readonly bannedSubject: string;
  /** Denormalized display name of the banned user; {@code null} when unknown. */
  readonly bannedUsername: string | null;
  readonly bannedBySubject: string;
  /** Denormalized display name of the moderator who issued the ban; {@code null} when unknown. */
  readonly bannedByUsername: string | null;
  readonly reason: string | null;
  readonly createdAt: string;
  /** ISO-8601 expiry; {@code null} = permanent ban. */
  readonly expiresAt: string | null;
}
