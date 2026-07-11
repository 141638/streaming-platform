/**
 * Request body for {@code POST /api/chat/v1/rooms/{roomKey}/bans}.
 * Mirrors {@code com.streaming.chat.api.dto.BanRequest}.
 *
 * The moderator identity (who issues the ban) is derived server-side from the
 * JWT {@code sub} — never sent from the client.
 */
export interface BanRequestDto {
  readonly bannedSubject: string;
  readonly reason?: string | null;
  /** Ban duration in seconds; {@code null}/omitted = permanent ban. */
  readonly durationSeconds?: number | null;
}
