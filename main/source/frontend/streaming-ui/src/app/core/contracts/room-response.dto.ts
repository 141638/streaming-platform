/**
 * Room metadata returned by the chat-service REST API.
 * Mirrors {@code com.streaming.chat.api.dto.RoomResponse}.
 */
export interface RoomResponseDto {
  readonly externalKey: string;
  readonly status: 'ACTIVE' | 'ARCHIVED';
  readonly createdAt: string;
  readonly archivedAt: string | null;
}
