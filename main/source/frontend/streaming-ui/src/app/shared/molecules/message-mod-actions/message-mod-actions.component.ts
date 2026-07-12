import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { displayName } from '../../../core/lib/avatar';

/**
 * The per-message moderator affordance. Dumb + OnPush: it renders a single
 * icon button that flips between "ban" and "unban" based on {@code isBanned},
 * and emits the matching intent. It carries an {@code aria-label} so it is
 * usable by keyboard/screen-reader; the parent row supplies the reveal-on-hover
 * styling (this button is opacity-driven, never removed from the tab order).
 */
@Component({
  selector: 'app-message-mod-actions',
  standalone: true,
  imports: [ButtonModule],
  templateUrl: './message-mod-actions.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageModActionsComponent {
  public readonly authorSubject = input.required<string>();
  public readonly authorUsername = input<string | null>(null);
  public readonly isBanned = input.required<boolean>();

  public readonly ban = output<void>();
  public readonly unban = output<void>();

  protected readonly authorLabel = computed(() =>
    displayName(this.authorSubject(), this.authorUsername()),
  );
  protected readonly actionLabel = computed(() =>
    this.isBanned()
      ? `Unban ${this.authorLabel()}`
      : `Ban ${this.authorLabel()}`,
  );

  // ── Public methods ─────────────────────────────────────────────────────

  protected onBan(): void {
    this.ban.emit();
  }

  protected onUnban(): void {
    this.unban.emit();
  }
}
