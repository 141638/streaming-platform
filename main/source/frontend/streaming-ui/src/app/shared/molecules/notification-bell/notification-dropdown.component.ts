import {
  CdkFixedSizeVirtualScroll,
  CdkVirtualForOf,
  CdkVirtualScrollViewport,
} from '@angular/cdk/scrolling';
import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { NotificationDto } from '../../../core/contracts/notification.dto';
import { NotificationService } from '../../../core/services/notification.service';
import { IdempotencyService } from '../../../core/services/idempotency.service';
import { NotificationCardComponent } from '../notification-card/notification-card.component';

/**
 * Dropdown panel shown when the notification bell is clicked.
 *
 * <p>Fetches the first page of notifications on init and supports
 * cursor-based infinite scroll via CDK virtual scrolling. Clicking a
 * card marks it as read and navigates to the target route when the
 * notification is actionable.
 */
@Component({
  selector: 'app-notification-dropdown',
  standalone: true,
  imports: [
    NotificationCardComponent,
    ButtonModule,
    ProgressSpinnerModule,
    RouterModule,
    CdkFixedSizeVirtualScroll,
    CdkVirtualForOf,
    CdkVirtualScrollViewport,
  ],
  templateUrl: './notification-dropdown.component.html',
  styleUrl: './notification-dropdown.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationDropdownComponent implements OnInit {
  private static readonly PAGE_SIZE = 20;
  private static readonly SCROLL_THRESHOLD_PX = 100;

  // ── Template decorators ──────────────────────────────────────────────────

  private readonly viewportRef = viewChild.required(CdkVirtualScrollViewport);

  // ── Outputs ──────────────────────────────────────────────────────────────

  /** Emitted when the user clicks the settings gear — parent closes the popover. */
  public readonly navigateAway = output<void>();

  // ── Injections ───────────────────────────────────────────────────────────

  private readonly notificationService = inject(NotificationService);
  private readonly idempotencyService = inject(IdempotencyService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  // ── State ────────────────────────────────────────────────────────────────

  protected readonly notifications = signal<NotificationDto[]>([]);
  protected readonly isLoading = signal(true);
  protected readonly isLoadingMore = signal(false);
  protected readonly isMarkingAllRead = signal(false);
  protected readonly hasMore = signal(false);
  protected readonly error = signal(false);

  private oldestCursor: string | null = null;

  // ── Lifecycle ────────────────────────────────────────────────────────────

  public ngOnInit(): void {
    this.loadFirstPage();
  }

  // ── Public methods ───────────────────────────────────────────────────────

  /** Retry loading the first page after an error — exposed for the Retry button. */
  public loadFirstPage(): void {
    this.isLoading.set(true);
    this.error.set(false);
    this.notificationService.getNotifications().subscribe({
      next: (list) => {
        this.notifications.set(list);
        this.hasMore.set(
          list.length >= NotificationDropdownComponent.PAGE_SIZE,
        );
        if (list.length > 0) {
          this.oldestCursor = list[list.length - 1].createdAt;
        }
        this.isLoading.set(false);
        this.cdr.detectChanges();
      },
      error: () => {
        this.isLoading.set(false);
        this.error.set(true);
        this.cdr.detectChanges();
      },
    });
  }

  /**
   * Handle scroll events on the CDK virtual scroll viewport.
   * Fires a fetch for the next page when the user scrolls within
   * {@link SCROLL_THRESHOLD_PX} of the bottom, guarded against duplicate
   * concurrent fetches via {@link isLoadingMore}.
   */
  public onScroll(): void {
    const vp = this.viewportRef();
    const el = vp.elementRef.nativeElement;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;

    if (
      distFromBottom < NotificationDropdownComponent.SCROLL_THRESHOLD_PX &&
      this.hasMore() &&
      !this.isLoadingMore()
    ) {
      this.loadMore();
    }
  }

  /** TrackBy for *cdkVirtualFor — stable identity by notification id. */
  public trackById(_index: number, n: NotificationDto): string {
    return n.id;
  }

  /** Mark as read on the server, refresh the badge, and navigate if actionable. */
  public onCardClick(notification: NotificationDto): void {
    if (!notification.read) {
      this.notificationService.markAsRead(notification.id, this.idempotencyService.newKey()).subscribe(() => {
        this.notificationService.refreshUnreadCount().subscribe();
      });
    }
    const route = notification.clickAction?.route;
    if (route) {
      this.router.navigateByUrl(route);
    }
  }

  /**
   * Mark every unread notification as read for the current user.
   *
   * <p>Uses a bulk API call instead of looping over individual
   * {@link onCardClick} calls. On success, all locally held notifications
   * are marked read and the bell badge is reset to zero.
   */
  public markAllAsRead(): void {
    this.isMarkingAllRead.set(true);
    this.notificationService.markAllAsRead(this.idempotencyService.newKey()).subscribe({
      next: () => {
        // Mark every locally held notification as read so the UI updates
        // without needing a full re-fetch.
        this.notifications.update((list) =>
          list.map((n) => (n.read ? n : { ...n, read: true })),
        );
        this.notificationService.refreshUnreadCount().subscribe();
        this.isMarkingAllRead.set(false);
        this.cdr.detectChanges();
      },
      error: () => {
        this.isMarkingAllRead.set(false);
        this.cdr.detectChanges();
      },
    });
  }

  // ── Private methods ──────────────────────────────────────────────────────

  /**
   * Append the next cursor-based page to the existing notification list.
   * Called by {@link onScroll} when the user scrolls near the bottom and
   * more results are available.
   */
  private loadMore(): void {
    this.isLoadingMore.set(true);
    this.notificationService
      .getNotifications(this.oldestCursor ?? undefined)
      .subscribe({
        next: (list) => {
          this.notifications.update((prev) => [...prev, ...list]);
          this.hasMore.set(
            list.length >= NotificationDropdownComponent.PAGE_SIZE,
          );
          if (list.length > 0) {
            this.oldestCursor = list[list.length - 1].createdAt;
          }
          this.isLoadingMore.set(false);
          this.cdr.detectChanges();
        },
        error: () => {
          this.isLoadingMore.set(false);
          this.cdr.detectChanges();
        },
      });
  }
}
