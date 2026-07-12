/**
 * Room metadata returned by the chat-service REST API.
 * Mirrors {@code com.streaming.chat.api.dto.RoomResponse}.
 */
export interface RoomResponseDto {
  readonly externalKey: string;
  readonly status: 'ACTIVE' | 'ARCHIVED';
  readonly createdAt: string;
  readonly archivedAt: string | null;
  /** Whether the current caller may moderate (ban/unban) this room. */
  readonly viewerCanModerate: boolean;
  /**
   * Whether the current caller is currently banned from this room. Lets a banned
   * user learn on room load (independent of the notification pipeline), not only
   * after a failed send.
   */
  readonly viewerBanned: boolean;
}
