import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, interval, Subscription, switchMap } from 'rxjs';
import { StreamService } from './stream.service';

/**
 * Manages viewer presence heartbeats for a stream.
 *
 * Usage from a component:
 * ```
 * this.presenceService.start(streamId);
 * // ... on destroy or stream change:
 * this.presenceService.stop();
 * ```
 */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly streamService = inject(StreamService);

  private readonly heartbeatIntervalMs = 15_000;
  private readonly viewerCountIntervalMs = 10_000;

  private heartbeatSub: Subscription | null = null;
  private viewerCountSub: Subscription | null = null;

  public readonly viewerCount = new BehaviorSubject<number>(0);

  /** Start sending heartbeats and polling viewer count. */
  public start(streamId: string): void {
    this.stop();

    // Heartbeat every 15s
    this.heartbeatSub = interval(this.heartbeatIntervalMs)
      .pipe(switchMap(() => this.streamService.sendHeartbeat(streamId)))
      .subscribe();

    // Poll viewer count every 10s
    this.viewerCountSub = interval(this.viewerCountIntervalMs)
      .pipe(switchMap(() => this.streamService.getViewerCount(streamId)))
      .subscribe({
        next: (res) => this.viewerCount.next(res.count),
        error: () => this.viewerCount.next(0),
      });

    // Fire immediately
    this.streamService.getViewerCount(streamId).subscribe({
      next: (res) => this.viewerCount.next(res.count),
      error: () => this.viewerCount.next(0),
    });
  }

  /** Stop heartbeats and viewer count polling. */
  public stop(): void {
    this.heartbeatSub?.unsubscribe();
    this.heartbeatSub = null;
    this.viewerCountSub?.unsubscribe();
    this.viewerCountSub = null;
    this.viewerCount.next(0);
  }
}
