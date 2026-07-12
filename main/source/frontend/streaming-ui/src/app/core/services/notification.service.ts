import { inject, Injectable } from '@angular/core';
import { NotificationDto } from '../contracts/notification.dto';
import { ToastService } from './toast.service';

/**
 * Central notification hub — receives notifications (today: mock; later: SSE)
 * and routes them to the appropriate display surface (toast, bell badge, …).
 *
 * ## Wave 2 (ADR-0007)
 * When the notification-service backend + Kafka infrastructure are ready:
 *   - SSE handshake via {@code @microsoft/fetch-event-source} (D1)
 *   - {@code subscribe(): Observable<NotificationDto[]>} — live stream
 *   - Bell unread-count signal fed from the same stream
 *   - All entry points call {@link pushNotification}, so toast behaviour
 *     stays identical regardless of source.
 *
 * Do NOT wire SSE until the two hard prerequisites in ADR-0007 are met.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly toastService = inject(ToastService);

  /** Push a notification to the toast surface. Idempotent — safe for replay. */
  public pushNotification(notification: NotificationDto): void {
    this.toastService.showNotification(notification);
  }

  /**
   * Pop a canned notification through the real toast pipeline so we can
   * visually verify the card molecule + toast host + sound without SSE.
   * Click the bell icon to exercise this path.
   */
  public testMock(): void {
    this.pushNotification({
      id: crypto.randomUUID(),
      category: 'moderation',
      severity: 'warn',
      title: 'You have been temporarily banned',
      message: 'Reason: spamming in chat. Your ban expires in 24 hours.',
      timestamp: new Date().toISOString(),
      read: false,
      action: { type: 'none' },
      sender: {
        username: 'moderator_alice',
        avatarUrl: 'https://api.dicebear.com/9.x/thumbs/svg?seed=moderator_alice',
        isSystem: false,
      },
    });

    // Fire a second variant after a short gap so both cards are visible at once.
    setTimeout(() => {
      this.pushNotification({
        id: crypto.randomUUID(),
        category: 'stream',
        severity: 'info',
        title: 'Stream starting soon',
        message:
          'Your followed channel "RandomUser1" goes live in 15 minutes.',
        timestamp: new Date().toISOString(),
        read: false,
        action: { type: 'navigate', route: '/@randomuser1' },
        sender: {
          username: 'randomuser1',
          avatarUrl: 'https://api.dicebear.com/9.x/thumbs/svg?seed=randomuser1',
          isSystem: false,
        },
      });
    }, 800);

    // Fire a system notification third — no sender → bottts avatar.
    setTimeout(() => {
      this.pushNotification({
        id: crypto.randomUUID(),
        category: 'system',
        severity: 'info',
        title: 'Account verified',
        message:
          'Your email address has been verified. You now have full access to all features.',
        timestamp: new Date(Date.now() - 86_400_000 * 2).toISOString(), // 2 days ago
        read: false,
      });
    }, 1600);
  }

  // Wave 2 — SSE
  // public subscribe(): Observable<NotificationDto[]> {
  //   …
  // }
}
