/**
 * The chat-service error envelope.
 * Mirrors {@code com.streaming.chat.api.error.ChatApiError}.
 *
 * Note: {@code 401} responses use a different shape ({@code error} /
 * {@code error_code} / {@code message}) handled by the auth interceptor.
 */
export interface ChatApiErrorDto {
  readonly code: string;
  readonly message: string;
}
