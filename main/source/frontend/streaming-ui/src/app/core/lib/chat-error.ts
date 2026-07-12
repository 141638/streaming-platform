import { HttpErrorResponse } from '@angular/common/http';
import { ChatApiErrorDto } from '../contracts/chat-api-error.dto';

/**
 * Safely extract the chat-service error envelope from an HTTP error.
 * Returns {@code null} when the body is not a {@code { code, message }} shape
 * (e.g. the {@code 401} auth-error envelope or a network failure).
 */
export function parseChatApiError(err: unknown): ChatApiErrorDto | null {
  if (!(err instanceof HttpErrorResponse)) {
    return null;
  }
  const body: unknown = err.error;
  if (
    body !== null &&
    typeof body === 'object' &&
    typeof (body as ChatApiErrorDto).code === 'string' &&
    typeof (body as ChatApiErrorDto).message === 'string'
  ) {
    const envelope = body as ChatApiErrorDto;
    return { code: envelope.code, message: envelope.message };
  }
  return null;
}

const FRIENDLY_MESSAGES: Readonly<Record<string, string>> = {
  CHAT_USER_BANNED:
    "You've been banned from this chat room and can't send messages.",
  AUTHZ_DENIED: "You don't have permission to do that.",
  CHAT_ROOM_ARCHIVED: 'This room has been archived.',
  CHAT_ROOM_NOT_FOUND: 'This chat room could not be found.',
};

/** Map a known chat error {@code code} to user-facing text, with a safe default. */
export function friendlyChatMessage(code: string): string {
  return FRIENDLY_MESSAGES[code] ?? 'Something went wrong. Please try again.';
}
