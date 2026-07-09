/**
 * A chat message as returned by the chat-service REST API.
 * Mirrors {@code com.streaming.chat.api.dto.MessageResponse}.
 */
export interface ChatMessageResponseDto {
  readonly id: string;
  readonly roomKey: string;
  readonly authorSubject: string;
  readonly authorUsername: string | null;
  readonly authorAvatarUrl: string | null;
  readonly body: string;
  readonly messageType: MessageType;
  readonly giftAmount: number | null;
  readonly giftCurrency: string | null;
  readonly createdAt: string;
}

export enum MessageType {
  NORMAL = 'NORMAL',
  SUPER_CHAT = 'SUPER_CHAT',
  SYSTEM = 'SYSTEM',
}
