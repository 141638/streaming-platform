import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { finalize } from 'rxjs';
import { friendlyChatMessage, parseChatApiError } from '../../../core/lib/chat-error';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import { BanListItemComponent } from '../../molecules/ban-list-item/ban-list-item.component';

/**
 * The moderation roster. Smart + OnPush: it drives the room-scoped
 * {@link ChatModerationService} (resolved from the chat-panel provider),
 * loads the active bans on init, and delegates row rendering to
 * {@link BanListItemComponent}. Expiry filtering + the live tick live in the
 * service, so this panel only owns transient load/error UI state.
 */
@Component({
  selector: 'app-ban-list-panel',
  standalone: true,
  imports: [
    ButtonModule,
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

  // ── Lifecycle ──────────────────────────────────────────────────────────

  public ngOnInit(): void {
    this.loadBans();
  }

  // ── Public methods ─────────────────────────────────────────────────────

  protected onRefresh(): void {
    this.loadBans();
  }

  protected onUnban(subject: string): void {
    // The service removes optimistically and restores on error.
    this.mod
      .unban(this.roomKey(), subject)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) => this.error.set(this.toMessage(err)),
      });
  }

  // ── Private methods ────────────────────────────────────────────────────

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
