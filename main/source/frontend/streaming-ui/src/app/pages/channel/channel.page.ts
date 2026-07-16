import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  NavigationEnd,
  Router,
  RouterModule,
  RouterOutlet,
} from '@angular/router';
import { ButtonDirective } from 'primeng/button';
import { TabsModule } from 'primeng/tabs';
import { filter, take } from 'rxjs';
import { ChannelIdentityResponseDto } from '../../core/contracts/channel-identity-response.dto';
import { AuthService } from '../../core/services/auth.service';
import { StreamService } from '../../core/services/stream.service';
import { SubscriptionService } from '../../core/services/subscription.service';
import { ToastService } from '../../core/services/toast.service';
import { ChannelHeaderComponent } from '../../shared/organisms/channel-header/channel-header.component';

type ChannelTab = 'home' | 'video' | 'about';

/**
 * Channel page layout shell — owns the {@code /@username} route tree.
 *
 * <p>Fetches channel identity once and renders the tab list. Each tab is
 * a child route rendered via {@code <router-outlet>}. Tab selection is
 * derived from the active URL so deep-linking and browser back/forward
 * work naturally.
 */
@Component({
  selector: 'app-channel-page',
  standalone: true,
  imports: [
    RouterModule,
    RouterOutlet,
    TabsModule,
    ChannelHeaderComponent,
    ButtonDirective,
  ],
  templateUrl: './channel.page.html',
  styleUrl: './channel.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelPage implements OnInit {
  public readonly username = input.required<string>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);
  private readonly streamService = inject(StreamService);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly toastService = inject(ToastService);

  protected readonly channel = signal<ChannelIdentityResponseDto | null>(null);
  protected readonly verified = computed(
    () => this.channel()?.verified ?? false,
  );

  protected readonly isOwner = computed(
    () => this.authService.myUsername() === this.username(),
  );

  // ── Follow state ────────────────────────────────────────────────────────

  protected readonly isFollowing = signal(false);
  protected readonly subscriptionId = signal<string | null>(null);
  protected readonly isFollowLoading = signal(false);
  /** Extracted from channel identity — null until loaded or on backfill gaps. */
  protected readonly broadcasterSubject = signal<string | null>(null);

  public ngOnInit(): void {
    // Redirect bare /@username to /@username/home so the child outlet
    // has a matching route on initial load. Don't fetch on this instance —
    // the redirect may destroy and recreate the component, and an HTTP
    // request started here would be aborted mid-flight.
    const path = this.router.url.split('?')[0];
    if (
      !path.endsWith('/home') &&
      !path.endsWith('/video') &&
      !path.endsWith('/about')
    ) {
      this.router.navigateByUrl(`/@${this.username()}/home`, {
        replaceUrl: true,
      });

      // Subscribe to NavigationEnd so the fetch fires after the redirect
      // completes — whether this component instance survives the redirect
      // or a new one is created (where ngOnInit won't need to redirect).
      this.router.events
        .pipe(
          filter((e): e is NavigationEnd => e instanceof NavigationEnd),
          take(1),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe(() => this.loadChannelIdentity());
      return;
    }

    // Already at the final URL — fetch immediately.
    this.loadChannelIdentity();

    // Keep activeTab in sync with the URL for browser back/forward.
    // Don't use takeUntilDestroyed here — the subscription must survive
    // for the lifetime of this component so every NavigationEnd is tracked.
    this.router.events
      .pipe(
        filter((e): e is NavigationEnd => e instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.activeTab.set(this.tabFromUrl());
      });
  }

  /** Derive selected tab from the current URL path (query-string aware). */
  private tabFromUrl(): ChannelTab {
    const path = this.router.url.split('?')[0];
    if (path.endsWith('/video')) return 'video';
    if (path.endsWith('/about')) return 'about';
    return 'home';
  }

  /** Writable signal — updated immediately on click, synced from URL on nav. */
  protected readonly activeTab = signal<ChannelTab>(this.tabFromUrl());

  protected onTabChange(value: string): void {
    this.activeTab.set(value as ChannelTab);
    this.router.navigateByUrl(`/@${this.username()}/${value}`);
  }

  /** Fetch channel identity for the header. One-shot — no takeUntilDestroyed. */
  private loadChannelIdentity(): void {
    this.streamService.getChannelIdentity(this.username()).subscribe({
      next: (res) => {
        this.channel.set(res);
        const subject = res.broadcasterSubject ?? null;
        this.broadcasterSubject.set(subject);
        if (subject) {
          this.checkFollowState(subject);
        }
      },
      error: (err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        this.channel.set(null);
      },
    });
  }

  /** Check whether the current user already follows this channel. */
  private checkFollowState(broadcasterSubject: string): void {
    this.subscriptionService
      .checkSubscription('CHANNEL', broadcasterSubject)
      .subscribe({
        next: (sub) => {
          this.isFollowing.set(sub.active);
          this.subscriptionId.set(sub.id);
        },
        error: () => {
          // 404 = not following — expected, not an error.
        },
      });
  }

  /** Follow this channel — optimistic UI with error toast on failure. */
  public onFollow(): void {
    const subject = this.broadcasterSubject();
    if (!subject) return;
    this.isFollowLoading.set(true);
    this.subscriptionService.follow('CHANNEL', subject).subscribe({
      next: (sub) => {
        this.isFollowing.set(true);
        this.subscriptionId.set(sub.id);
        this.isFollowLoading.set(false);
      },
      error: (err: unknown) => {
        // 409 Conflict = already following — check real state from server.
        if ((err as { status?: number })?.status === 409) {
          this.checkFollowState(subject);
          this.isFollowLoading.set(false);
          return;
        }
        this.toastService.showError('Failed to follow', 'Please try again.');
        this.isFollowLoading.set(false);
      },
    });
  }

  /** Unfollow this channel — optimistic UI with error toast on failure. */
  public onUnfollow(): void {
    const id = this.subscriptionId();
    if (!id) return;
    this.isFollowLoading.set(true);
    this.subscriptionService.unfollow(id).subscribe({
      next: () => {
        this.isFollowing.set(false);
        this.subscriptionId.set(null);
        this.isFollowLoading.set(false);
      },
      error: () => {
        this.toastService.showError('Failed to unfollow', 'Please try again.');
        this.isFollowLoading.set(false);
      },
    });
  }
}
