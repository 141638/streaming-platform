/** Category of notification — drives iconography and grouping in the bell menu. */
export type NotificationCategory =
  | 'moderation'
  | 'stream'
  | 'membership'
  | 'system';

/** Mirrors PrimeNG Message.severity for direct toast mapping. */
export type NotificationSeverity = 'info' | 'success' | 'warn' | 'error';

/** What happens when the user clicks the card. Extend this union as real use cases arrive. */
export type NotificationActionType = 'navigate' | 'none';

export interface NotificationAction {
  readonly type: NotificationActionType;
  /** Angular route path — only meaningful when type is 'navigate'. */
  readonly route?: string;
}

/** Sender metadata shown alongside the notification content — undefined = system message. */
export interface NotificationSender {
  /** Display name for tooltip / alt. Empty string treated as system-generated. */
  readonly username: string;
  /** Undefined → render default fallback avatar. */
  readonly avatarUrl?: string;
  /** True → render a system/cog icon instead of a user avatar. No-op on click. */
  readonly isSystem: boolean;
}

/**
 * Normalised notification shape consumed by both the toast and the future
 * bell-dropdown.  Aligned with notification-service's planned REST / SSE payload
 * so the frontend DTO stays a thin mirror.
 */
export interface NotificationDto {
  readonly id: string;
  readonly category: NotificationCategory;
  readonly severity: NotificationSeverity;
  readonly title: string;
  readonly message: string;
  /** ISO‑8601 */
  readonly timestamp: string;
  readonly read: boolean;
  /** Undefined = no action (same as 'none'). */
  readonly action?: NotificationAction;
  /** Undefined = system-generated notification with no sender avatar. */
  readonly sender?: NotificationSender;
}

/** Lightweight envelope the ToastService carries internally. */
export interface ToastDto {
  readonly severity: NotificationSeverity;
  readonly summary: string;
  readonly detail: string;
  readonly data?: NotificationDto;
}
