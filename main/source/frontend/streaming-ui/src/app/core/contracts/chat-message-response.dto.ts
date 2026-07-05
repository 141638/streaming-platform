/**
 * A chat message as returned by the chat-service REST API.
 * Mirrors {@code com.streaming.chat.api.dto.MessageResponse}.
 */
export interface ChatMessageResponseDto {
  readonly id: string;
  readonly roomKey: string;
  readonly authorSubject: string;
  readonly body: string;
  readonly createdAt: string;
}
