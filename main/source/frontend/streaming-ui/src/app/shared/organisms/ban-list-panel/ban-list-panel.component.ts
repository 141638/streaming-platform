import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { finalize } from 'rxjs';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { displayName } from '../../../core/lib/avatar';
import { friendlyChatMessage, parseChatApiError } from '../../../core/lib/chat-error';
import { formatExpiresIn } from '../../../core/lib/time';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import {
  BanListItemComponent,
  DurationChange,
} from '../../molecules/ban-list-item/ban-list-item.component';

/**
 * The moderation roster. Smart + OnPush: it drives the room-scoped
 * {@link ChatModerationService} (resolved from the chat-panel provider),
 * loads the active bans on init, and delegates row rendering to
 * {@link BanListItemComponent}. Expiry filtering + the live tick live in the
 * service, so this panel only owns transient load/error UI state plus the
 * unban-confirm dialog. A roster unban is confirmed first (the inline unban from
 * a message row stays immediate); a row's duration-ladder edit is applied via
 * {@code updateDuration}.
 */
@Component({
  selector: 'app-ban-list-panel',
  standalone: true,
  imports: [
    ButtonModule,
    DialogModule,
    MessageModule,
    ProgressSpinnerModule,
    BanListItemComponent,
  ],
  templateUrl: './ban-list-panel.component.html',
  styleUrl: './ban-list-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BanListPanelComponent implements OnInit {
  public readonly roomKey = input.required<string>();

  protected readonly mod = inject(ChatModerationService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pendingUnban = signal<BanResponseDto | null>(null);

  protected readonly pendingUnbanName = computed(() => {
    const ban = this.pendingUnban();
    return ban === null
      ? ''
      : displayName(ban.bannedSubject, ban.bannedUsername);
  });
  protected readonly pendingUnbanExpiry = computed(() => {
    const ban = this.pendingUnban();
    if (ban === null) {
      return '';
    }
    return ban.expiresAt === null
      ? 'is permanent'
      : formatExpiresIn(ban.expiresAt, this.mod.now());
  });

  // ── Lifecycle ──────────────────────────────────────────────────────────

  public ngOnInit(): void {
    this.loadBans();
  }

  // ── Public methods ─────────────────────────────────────────────────────

  protected onRefresh(): void {
    this.loadBans();
  }

  /** Open the confirm dialog for a roster unban (a message-row unban stays immediate). */
  protected onUnbanRequest(subject: string): void {
    const ban =
      this.mod.activeBans().find((b) => b.bannedSubject === subject) ?? null;
    this.pendingUnban.set(ban);
  }

  protected cancelUnban(): void {
    this.pendingUnban.set(null);
  }

  /**
   * The dialog binds {@code visible} one-way off an object signal, so reconcile
   * our state whenever PrimeNG drives {@code visibleChange} to false (X / mask /
   * ESC). Handling this synchronously — rather than the post-animation onHide —
   * avoids the re-render reasserting {@code visible=true} mid-close.
   */
  protected onUnbanDialogVisibleChange(visible: boolean): void {
    if (!visible) {
      this.cancelUnban();
    }
  }

  protected confirmUnban(): void {
    const ban = this.pendingUnban();
    this.pendingUnban.set(null);
    if (ban === null) {
      return;
    }
    // The service removes optimistically and restores on error.
    this.mod
      .unban(this.roomKey(), ban.bannedSubject)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) => this.error.set(this.toMessage(err)),
      });
  }

  /** Re-base a ban's duration from a row's inline ladder editor. */
  protected onDurationChange(change: DurationChange): void {
    this.mod
      .updateDuration(this.roomKey(), change.subject, change.durationSeconds)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) => this.error.set(this.toMessage(err)),
      });
  }

  // ── Private methods ──────────────────────────────────────────────────────

  private loadBans(): void {
    this.loading.set(true);
    this.error.set(null);
    this.mod
      .loadBans(this.roomKey())
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        error: (err: unknown) => this.error.set(this.toMessage(err)),
      });
  }

  /** Map a chat-service error envelope to friendly copy, with a safe default. */
  private toMessage(err: unknown): string {
    const parsed = parseChatApiError(err);
    return parsed === null
      ? 'Could not load banned users. Please try again.'
      : friendlyChatMessage(parsed.code);
  }
}
