import { Injectable, NgZone, inject, signal } from '@angular/core';
import {
  EventSourceMessage,
  fetchEventSource,
} from '@microsoft/fetch-event-source';
import { Subject } from 'rxjs';
import { AuthService } from './auth.service';

export interface StreamSseEventDto {
  type: string; // "stream:started" | "stream:ended" | "stream:viewers"
  streamId: string;
  status?: string; // "LIVE" | "ENDED"
  viewerCount?: number;
}

/**
 * SSE client for stream lifecycle events from {@code GET /v1/streams/events}.
 *
 * Handshake failures are retried every 5-10 s with jitter.  The retry loop
 * runs entirely outside Angular's zone so polling never triggers change
 * detection cycles — only event delivery re-enters the zone.
 */
@Injectable({ providedIn: 'root' })
export class StreamSseService {
  private readonly authService = inject(AuthService);
  private readonly ngZone = inject(NgZone);
  private readonly basePath = '/api/streams/v1';

  private sseAbortController: AbortController | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private currentStreamId: string | undefined;

  /** Whether the SSE handshake succeeded and the connection is open. */
  readonly connected = signal(false);

  private readonly streamStartedSubject = new Subject<StreamSseEventDto>();
  readonly streamStarted$ = this.streamStartedSubject.asObservable();

  private readonly streamEndedSubject = new Subject<StreamSseEventDto>();
  readonly streamEnded$ = this.streamEndedSubject.asObservable();

  private readonly streamViewersSubject = new Subject<StreamSseEventDto>();
  readonly streamViewers$ = this.streamViewersSubject.asObservable();

  /**
   * Connect to the SSE stream.
   *
   * @param streamId optional — when set, only events for this stream are delivered
   */
  connect(streamId?: string): void {
    this.disconnect();
    this.currentStreamId = streamId;
    this.ngZone.runOutsideAngular(() => this.doConnect());
  }

  /** Abort the active SSE connection and clear any pending retry timer. */
  disconnect(): void {
    this.clearRetryTimer();
    this.sseAbortController?.abort();
    this.sseAbortController = null;
    this.connected.set(false);
  }

  // -- internals -----------------------------------------------------------

  private doConnect(): void {
    const token = this.authService.accessToken();
    if (!token) {
      this.scheduleRetry();
      return;
    }

    this.sseAbortController = new AbortController();
    const url = this.currentStreamId
      ? `${this.basePath}/streams/events?streamId=${encodeURIComponent(this.currentStreamId)}`
      : `${this.basePath}/streams/events`;

    fetchEventSource(url, {
      headers: { Authorization: `Bearer ${token}` },
      signal: this.sseAbortController.signal,
      openWhenHidden: true,
      onopen: async (response) => {
        if (response.ok) {
          this.ngZone.run(() => this.connected.set(true));
          return;
        }
        const status = response.status;
        if (status === 401 || status === 403) {
          throw new Error(`SSE auth failed: ${status}`);
        }
        throw new Error(`SSE handshake failed: ${status}`);
      },
      onmessage: (msg: EventSourceMessage) => {
        if (!msg.event || !msg.data) {
          return;
        }
        try {
          const data = JSON.parse(msg.data) as StreamSseEventDto;
          this.ngZone.run(() => {
            switch (msg.event) {
              case 'stream:started':
                this.streamStartedSubject.next(data);
                break;
              case 'stream:ended':
                this.streamEndedSubject.next(data);
                break;
              case 'stream:viewers':
                this.streamViewersSubject.next(data);
                break;
            }
          });
        } catch {
          // Malformed event body — ignore and keep listening
        }
      },
      onerror: (err) => {
        this.ngZone.run(() => this.connected.set(false));
        if (err instanceof Error && err.message.startsWith('SSE auth failed')) {
          throw err; // fatal — stop all retries
        }
        // Transient error — schedule our own retry, throw to stop the
        // library's built-in exponential backoff so we control the interval.
        this.scheduleRetry();
        throw err;
      },
      onclose: () => {
        this.ngZone.run(() => this.connected.set(false));
        this.scheduleRetry();
      },
    });
  }

  /**
   * Schedule a reconnect attempt after 5-10 s with random jitter.
   *
   * Uses {@code setTimeout} (not RxJS timers) so execution stays outside
   * Angular's zone and never triggers a change-detection cycle.
   */
  private scheduleRetry(): void {
    this.clearRetryTimer();
    const delay = 5000 + Math.random() * 5000; // 5-10 s with jitter
    this.retryTimer = setTimeout(() => {
      this.doConnect();
    }, delay);
  }

  private clearRetryTimer(): void {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }
}
