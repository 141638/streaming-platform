/**
 * Backend notification categories — mirrors {@code NotificationCategory.wireValue()}.
 * Extend this union when new categories are added to the backend enum.
 */
export type BackendNotificationCategory =
  | 'STREAM_LIVE'
  | 'STREAM_ENDED'
  | 'CHAT_MENTION'
  | 'CHAT_MODERATION'
  | 'SYSTEM';

/** Mirrors PrimeNG Message.severity for direct toast mapping. */
export type NotificationSeverity = 'info' | 'success' | 'warn' | 'error';

/** What happens when the user clicks the card. */
export type NotificationActionType = 'navigate' | 'none';

export interface NotificationAction {
  readonly type: NotificationActionType;
  /** Angular route path — only meaningful when type is 'navigate'. */
  readonly route?: string;
}

/**
 * Normalised notification shape consumed by both the toast and the bell
 * dropdown.  Aligned with notification-service {@code NotificationResponse}.
 */
/** Sender metadata shown alongside the notification — undefined = system message. */
export interface NotificationSender {
  readonly username: string;
  readonly avatarUrl?: string;
  readonly isSystem: boolean;
}

export interface NotificationDto {
  readonly id: string;
  readonly category: BackendNotificationCategory;
  /** Stable machine-readable action key e.g. {@code "stream.started"}. */
  readonly action: string;
  readonly title: string;
  readonly message: string;
  /** Arbitrary JSON payload — parsed client-side per-category. */
  readonly metadata: string | null;
  readonly read: boolean;
  /** ISO‑8601 */
  readonly createdAt: string;
  /** Derived from {@link category} — drives toast accent colour. */
  readonly severity: NotificationSeverity;
  /** Derived from {@link action} + {@link metadata} — drives card click. */
  readonly clickAction?: NotificationAction;
  /** Undefined = system-generated notification with no sender avatar. */
  readonly sender?: NotificationSender;
}

// -- backend wire types ----------------------------------------------------

/** Shape returned by {@code GET /v1/notifications} and the SSE stream. */
export interface NotificationResponseDto {
  readonly id: string;
  readonly category: string;
  readonly action: string;
  readonly title: string;
  readonly body: string;
  readonly metadata: string | null;
  /** {@code @JsonProperty("read")} on the backend. */
  readonly read: boolean;
  /** ISO‑8601 */
  readonly createdAt: string;
}

/** Shape returned by {@code GET /v1/notifications/unread-count}. */
export interface UnreadCountResponseDto {
  readonly count: number;
}

/** Lightweight envelope the ToastService carries internally. */
export interface ToastDto {
  readonly severity: NotificationSeverity;
  readonly summary: string;
  readonly detail: string;
  readonly data?: NotificationDto;
}

// -- mapping helpers -------------------------------------------------------

/** Derive a toast severity from the backend notification category. */
export function severityFromCategory(category: string): NotificationSeverity {
  switch (category) {
    case 'STREAM_LIVE':
      return 'success';
    case 'STREAM_ENDED':
      return 'info';
    case 'CHAT_MENTION':
      return 'info';
    case 'CHAT_MODERATION':
      return 'warn';
    case 'SYSTEM':
      return 'info';
    default:
      return 'info';
  }
}

/**
 * Derive a click action from the notification's action key and metadata.
 *
 * <p>Known actions:
 * <ul>
 *   <li>{@code stream.started} → navigate to the stream detail page
 *   <li>{@code stream.ended} → navigate to the stream detail page
 *   <li>{@code chat.banned} → no navigation (user is banned from the room)
 *   <li>{@code chat.unbanned} → navigate to the chat room
 *   <li>{@code chat.moderator_alert} → navigate to the chat room (broadcaster)
 * </ul>
 *
 * <p>Navigation targets will change after fan-out wiring (5.2b):
 * broadcasters go to {@code /channel/:id}, followers go to
 * {@code /watch/:id}.
 */
export function actionFromNotification(
  action: string,
  metadata: string | null,
): NotificationAction {
  switch (action) {
    case 'stream.started':
    case 'stream.ended': {
      const streamId = parseField(metadata, 'streamId');
      if (streamId) {
        return { type: 'navigate', route: `/watch/${streamId}` };
      }
      return { type: 'none' };
    }
    case 'chat.banned':
      // Banned users cannot access the room — no navigation.
      return { type: 'none' };
    case 'chat.unbanned': {
      // User can rejoin the room after the ban is lifted.
      const roomKey = parseField(metadata, 'roomKey');
      if (roomKey) {
        return { type: 'navigate', route: `/chat/${roomKey}` };
      }
      return { type: 'none' };
    }
    case 'chat.moderator_alert': {
      // Broadcaster clicks to navigate to the room for context.
      const roomKey = parseField(metadata, 'roomKey');
      if (roomKey) {
        return { type: 'navigate', route: `/chat/${roomKey}` };
      }
      return { type: 'none' };
    }
    default:
      return { type: 'none' };
  }
}

/** Try to extract a string field from notification metadata JSON. */
function parseField(metadata: string | null, field: string): string | null {
  if (!metadata) return null;
  try {
    const parsed = JSON.parse(metadata) as Record<string, unknown>;
    return typeof parsed[field] === 'string'
      ? (parsed[field] as string)
      : null;
  } catch {
    return null;
  }
}

/** Map a backend {@link NotificationResponseDto} to the frontend DTO. */
export function mapNotificationResponse(dto: NotificationResponseDto): NotificationDto {
  return {
    id: dto.id,
    category: dto.category as BackendNotificationCategory,
    action: dto.action,
    title: dto.title,
    message: dto.body,
    metadata: dto.metadata,
    read: dto.read,
    createdAt: dto.createdAt,
    severity: severityFromCategory(dto.category),
    clickAction: actionFromNotification(dto.action, dto.metadata),
    sender: senderFromMetadata(dto.category, dto.action, dto.metadata),
  };
}

/**
 * Derive a sender object from notification metadata so the card can show
 * who performed a moderation action or sent a chat mention.
 *
 * <p>Returns {@code undefined} for system-generated notifications
 * (stream lifecycle, system announcements) — the card hides the avatar slot.
 */
function senderFromMetadata(
  category: string,
  _action: string,
  metadata: string | null,
): NotificationSender | undefined {
  if (category === 'CHAT_MODERATION') {
    const username = parseField(metadata, 'bannedByUsername');
    if (username) {
      return { username, isSystem: false };
    }
    return { username: 'Moderator', isSystem: true };
  }
  if (category === 'CHAT_MENTION') {
    const username = parseField(metadata, 'mentionedBy');
    if (username) {
      return { username, isSystem: false };
    }
    return { username: 'Someone', isSystem: true };
  }
  return undefined;
}
