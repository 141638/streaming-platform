/**
 * Request body for {@code POST /api/chat/v1/rooms/{roomKey}/bans}.
 * Mirrors {@code com.streaming.chat.api.dto.BanRequest}.
 *
 * The moderator identity (who issues the ban) is derived server-side from the
 * JWT {@code sub} — never sent from the client.
 */
export interface BanRequestDto {
  readonly bannedSubject: string;
  /**
   * Denormalized display name of the banned user, supplied by the client (it
   * already holds it from the message author). Stored on the ban so the roster
   * renders names without a cross-service lookup; {@code null}/omitted falls back
   * to the truncated subject.
   */
  readonly bannedUsername?: string | null;
  readonly reason?: string | null;
  /** Ban duration in seconds; {@code null}/omitted = permanent ban. */
  readonly durationSeconds?: number | null;
}
