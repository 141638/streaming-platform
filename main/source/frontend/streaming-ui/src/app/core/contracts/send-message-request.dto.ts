/**
 * Request body for sending a chat message.
 * Note: no {@code author} field — the server derives it from the JWT.
 */
export interface SendMessageRequestDto {
  readonly content: string;
}
