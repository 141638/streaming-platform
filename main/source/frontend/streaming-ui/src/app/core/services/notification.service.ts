import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  BehaviorSubject,
  map,
  Observable,
  tap,
} from 'rxjs';
import {
  mapNotificationResponse,
  NotificationDto,
  NotificationResponseDto,
  UnreadCountResponseDto,
} from '../contracts/notification.dto';
import { AuthService } from './auth.service';
import { IdempotencyService } from './idempotency.service';
import { ToastService } from './toast.service';
import {
  EventSourceMessage,
  fetchEventSource,
} from '@microsoft/fetch-event-source';

/**
 * Central notification hub — REST fetching, SSE subscription, and routing
 * to the toast surface + bell badge.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);
  private readonly toastService = inject(ToastService);
  private readonly authService = inject(AuthService);

  private readonly basePath = '/api/notifications/v1';

  /** Controller to abort the active SSE connection. */
  private sseAbortController: AbortController | null = null;

  /** Reactive unread count for the bell badge. */
  private readonly unreadCountSubject = new BehaviorSubject<number>(0);
  public readonly unreadCount$ = this.unreadCountSubject.asObservable();

  // -- REST ----------------------------------------------------------------

  /**
   * Fetch the current user's notifications, newest first.
   *
   * @param cursor ISO‑8601 timestamp of the oldest notification currently
   *               displayed, or {@code undefined} for the first page.
   * @param limit  max results (default 20)
   */
  public getNotifications(
    cursor?: string,
    limit: number = 20,
  ): Observable<NotificationDto[]> {
    let url = `${this.basePath}/notifications?limit=${limit}`;
    if (cursor) {
      url += `&cursor=${encodeURIComponent(cursor)}`;
    }
    return this.http
      .get<NotificationResponseDto[]>(url)
      .pipe(map((list) => list.map(mapNotificationResponse)));
  }

  /** Fetch the unread count and update the local subject. */
  public refreshUnreadCount(): Observable<number> {
    return this.http
      .get<UnreadCountResponseDto>(`${this.basePath}/notifications/unread-count`)
      .pipe(
        tap((dto) => this.unreadCountSubject.next(dto.count)),
        map((dto) => dto.count),
      );
  }

  /** Mark a notification as read on the server. */
  public markAsRead(
    id: string,
    idempotencyKey?: string,
  ): Observable<NotificationResponseDto> {
    return this.http.post<NotificationResponseDto>(
      `${this.basePath}/notifications/${encodeURIComponent(id)}/read`,
      null,
      IdempotencyService.options(idempotencyKey),
    );
  }

  /** Mark all unread notifications as read for the current user. */
  public markAllAsRead(
    idempotencyKey?: string,
  ): Observable<UnreadCountResponseDto> {
    return this.http.post<UnreadCountResponseDto>(
      `${this.basePath}/notifications/read-all`,
      null,
      IdempotencyService.options(idempotencyKey),
    );
  }

  // -- push routing --------------------------------------------------------

  /** Push a notification to the toast surface. Idempotent — safe for replay. */
  public pushNotification(notification: NotificationDto): void {
    this.toastService.showNotification(notification);
  }

  // -- SSE -----------------------------------------------------------------

  /**
   * Open an SSE connection to {@code GET /v1/notifications/stream}.
   *
   * Uses {@code @microsoft/fetch-event-source} for Bearer-token auth and
   * auto-reconnect with jitter.  Incoming {@code notification} events are
   * mapped through {@link mapNotificationResponse} and pushed to the toast
   * surface.  The unread count is refreshed after each event.
   *
   * Safe to call when already connected — the previous connection is aborted
   * before opening a new one.
   */
  public connect(): void {
    this.disconnect();

    const token = this.authService.accessToken();
    if (!token) {
      return;
    }

    this.sseAbortController = new AbortController();

    fetchEventSource(`${this.basePath}/notifications/stream`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: this.sseAbortController.signal,
      onopen: async (response) => {
        if (!response.ok) {
          const status = response.status;
          if (status === 401 || status === 403) {
            // Token expired mid-stream — throw to stop reconnection.
            // The auth effect in AppComponent will reconnect after refresh.
            throw new Error(`SSE auth failed: ${status}`);
          }
        }
      },
      onmessage: (msg: EventSourceMessage) => {
        if (msg.event === 'notification') {
          try {
            const dto: NotificationResponseDto = JSON.parse(msg.data);
            this.pushNotification(mapNotificationResponse(dto));
            this.refreshUnreadCount().subscribe();
          } catch (e) {
            console.warn('Malformed SSE notification event, skipping', e);
          }
        }
      },
      onerror: (err) => {
        // Auth errors are fatal — stop retrying; the AppComponent effect
        // will reconnect after token refresh.
        if (err instanceof Error && err.message.startsWith('SSE auth failed')) {
          throw err;
        }
        // Transient errors (network blip, server restart): return void to
        // use the default retry backoff with jitter.
      },
      openWhenHidden: true,
    });
  }

  /** Close the active SSE connection, if any. */
  public disconnect(): void {
    if (this.sseAbortController) {
      this.sseAbortController.abort();
      this.sseAbortController = null;
    }
  }

  // -- dev helpers ---------------------------------------------------------

  /**
   * Pop a canned notification through the real toast pipeline so we can
   * visually verify the card + toast + sound without SSE.
   * Click the bell icon to exercise this path.
   */
  public testMock(): void {
    this.pushNotification({
      id: crypto.randomUUID(),
      category: 'CHAT_MODERATION',
      action: 'chat.banned',
      title: 'You have been temporarily banned',
      message: 'Reason: spamming in chat. Your ban expires in 24 hours.',
      metadata: null,
      read: false,
      createdAt: new Date().toISOString(),
      severity: 'warn',
      clickAction: { type: 'none' },
    });

    setTimeout(() => {
      this.pushNotification({
        id: crypto.randomUUID(),
        category: 'STREAM_LIVE',
        action: 'stream.started',
        title: 'Your stream is now live',
        message: 'Your stream is now broadcasting.',
        metadata: JSON.stringify({ streamId: crypto.randomUUID() }),
        read: false,
        createdAt: new Date().toISOString(),
        severity: 'success',
        clickAction: { type: 'none' },
      });
    }, 800);

    setTimeout(() => {
      this.pushNotification({
        id: crypto.randomUUID(),
        category: 'SYSTEM',
        action: 'system.info',
        title: 'Account verified',
        message:
          'Your email address has been verified. You now have full access to all features.',
        metadata: null,
        read: false,
        createdAt: new Date(Date.now() - 86_400_000 * 2).toISOString(),
        severity: 'info',
        clickAction: { type: 'none' },
      });
    }, 1600);
  }
}
