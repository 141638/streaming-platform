import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { SkeletonModule } from 'primeng/skeleton';
import { TagModule } from 'primeng/tag';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { PreferenceResponseDto } from '../../core/contracts/preference-response.dto';
import { SubscriptionResponseDto } from '../../core/contracts/subscription-response.dto';
import { SubscriptionService } from '../../core/services/subscription.service';
import { ToastService } from '../../core/services/toast.service';

/**
 * Notification settings page — manage follows and delivery preferences.
 *
 * <p>Two sections:
 * <ol>
 *   <li><strong>Following</strong> — list of active subscriptions with unfollow</li>
 *   <li><strong>Delivery channels</strong> — per-channel toggles (in_app, email)</li>
 * </ol>
 */
@Component({
  selector: 'app-notification-settings',
  standalone: true,
  imports: [
    CardModule,
    ButtonModule,
    TagModule,
    ToggleSwitchModule,
    SkeletonModule,
    FormsModule,
  ],
  templateUrl: './notification-settings.page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationSettingsPage implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly subscriptionService = inject(SubscriptionService);
  private readonly toastService = inject(ToastService);

  // ── Following section ────────────────────────────────────────────────────

  protected readonly subscriptions = signal<SubscriptionResponseDto[]>([]);
  protected readonly isSubscriptionsLoading = signal(true);

  // ── Preferences section ───────────────────────────────────────────────────

  protected readonly preferences = signal<PreferenceResponseDto[]>([]);
  protected readonly isPreferencesLoading = signal(true);

  /** Tracks which preference toggles are mid-flight to prevent double-clicks. */
  protected readonly togglingIds = signal<Set<string>>(new Set());

  public ngOnInit(): void {
    this.loadSubscriptions();
    this.loadPreferences();
  }

  // ── Data loading ─────────────────────────────────────────────────────────

  private loadSubscriptions(): void {
    this.isSubscriptionsLoading.set(true);
    this.subscriptionService
      .getMySubscriptions()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          // Only show active follows
          this.subscriptions.set(list.filter((s) => s.active));
          this.isSubscriptionsLoading.set(false);
        },
        error: () => this.isSubscriptionsLoading.set(false),
      });
  }

  private loadPreferences(): void {
    this.isPreferencesLoading.set(true);
    this.subscriptionService
      .getMyPreferences()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.preferences.set(list);
          this.isPreferencesLoading.set(false);
        },
        error: () => this.isPreferencesLoading.set(false),
      });
  }

  // ── Actions ──────────────────────────────────────────────────────────────

  public unfollow(sub: SubscriptionResponseDto): void {
    this.subscriptionService.unfollow(sub.id).subscribe({
      next: () => {
        this.subscriptions.update((list) =>
          list.filter((s) => s.id !== sub.id),
        );
      },
      error: () =>
        this.toastService.showError('Failed to unfollow', 'Please try again.'),
    });
  }

  public togglePreference(pref: PreferenceResponseDto): void {
    const ids = this.togglingIds();
    if (ids.has(pref.id)) return; // mid-flight guard

    const newActive = !pref.active;
    this.togglingIds.update((s) => new Set(s).add(pref.id));

    this.subscriptionService
      .updatePreference(pref.id, { active: newActive })
      .subscribe({
        next: (updated) => {
          this.preferences.update((list) =>
            list.map((p) => (p.id === updated.id ? updated : p)),
          );
          this.togglingIds.update((s) => {
            const next = new Set(s);
            next.delete(pref.id);
            return next;
          });
        },
        error: () => {
          this.toastService.showError(
            'Failed to update preference',
            'Please try again.',
          );
          this.togglingIds.update((s) => {
            const next = new Set(s);
            next.delete(pref.id);
            return next;
          });
        },
      });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Human-readable label for a target type. */
  protected targetTypeLabel(type: string): string {
    switch (type) {
      case 'CHANNEL':
        return 'Channel';
      case 'CHAT_ROOM':
        return 'Chat Room';
      case 'STREAM_SESSION':
        return 'Stream';
      default:
        return type;
    }
  }

  /** Human-readable label for a delivery channel. */
  protected channelLabel(channel: string): string {
    switch (channel) {
      case 'in_app':
        return 'In-App Notifications';
      case 'email':
        return 'Email Notifications';
      case 'push':
        return 'Push Notifications';
      default:
        return channel;
    }
  }
}
