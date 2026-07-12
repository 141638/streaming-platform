import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { dicebearAvatarUrl, displayName } from '../../../core/lib/avatar';
import { formatExpiresIn } from '../../../core/lib/time';

/** Absolute preset ladder the inline editor steps through, ascending; top = permanent. */
const DURATION_LADDER: readonly (number | null)[] = [3600, 86_400, 604_800, null];
const PERMANENT_RUNG = DURATION_LADDER.length - 1;

/** Payload emitted when a moderator steps a ban's duration up or down. */
export interface DurationChange {
  readonly subject: string;
  readonly durationSeconds: number | null;
}

/**
 * One roster row for the moderation ban list. Dumb + OnPush: it renders a single
 * {@link BanResponseDto}, emits the subject when the moderator lifts the ban, and
 * emits a {@link DurationChange} when they step the duration on the preset ladder
 * (1h → 24h → 7d → permanent). The live "expires in" copy is driven by the
 * {@code nowMs} tick from the parent, so the row never owns a timer of its own.
 */
@Component({
  selector: 'app-ban-list-item',
  standalone: true,
  imports: [ButtonModule],
  templateUrl: './ban-list-item.component.html',
  styleUrl: './ban-list-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BanListItemComponent {
  public readonly ban = input.required<BanResponseDto>();
  public readonly nowMs = input.required<number>();

  public readonly unban = output<string>();
  public readonly durationChange = output<DurationChange>();

  protected readonly avatarUrl = computed(() =>
    dicebearAvatarUrl(this.ban().bannedSubject),
  );
  protected readonly nameLabel = computed(() =>
    displayName(this.ban().bannedSubject, this.ban().bannedUsername),
  );
  protected readonly reasonText = computed(
    () => this.ban().reason ?? 'No reason given',
  );
  protected readonly bannedByLabel = computed(
    () =>
      `by ${displayName(this.ban().bannedBySubject, this.ban().bannedByUsername)}`,
  );
  protected readonly isPermanent = computed(() => this.ban().expiresAt === null);
  protected readonly durationLabel = computed(() => {
    const expiresAt = this.ban().expiresAt;
    return expiresAt === null
      ? 'Permanent'
      : formatExpiresIn(expiresAt, this.nowMs());
  });

  /** Nearest ladder index for the ban's original span (permanent pins to the top). */
  protected readonly currentRung = computed(() => {
    const ban = this.ban();
    if (ban.expiresAt === null) {
      return PERMANENT_RUNG;
    }
    const spanSeconds =
      (Date.parse(ban.expiresAt) - Date.parse(ban.createdAt)) / 1000;
    let nearest = 0;
    let smallestDelta = Number.POSITIVE_INFINITY;
    for (let i = 0; i < PERMANENT_RUNG; i++) {
      const delta = Math.abs((DURATION_LADDER[i] as number) - spanSeconds);
      if (delta < smallestDelta) {
        smallestDelta = delta;
        nearest = i;
      }
    }
    return nearest;
  });
  protected readonly canStepUp = computed(
    () => this.currentRung() < PERMANENT_RUNG,
  );
  protected readonly canStepDown = computed(() => this.currentRung() > 0);

  // ── Public methods ─────────────────────────────────────────────────────

  protected onUnban(): void {
    this.unban.emit(this.ban().bannedSubject);
  }

  protected onStepLonger(): void {
    this.stepTo(this.currentRung() + 1);
  }

  protected onStepShorter(): void {
    this.stepTo(this.currentRung() - 1);
  }

  // ── Private methods ─────────────────────────────────────────────────────

  /** Emit a duration change for a clamped ladder index; no-op when unchanged. */
  private stepTo(rung: number): void {
    const clamped = Math.max(0, Math.min(PERMANENT_RUNG, rung));
    if (clamped === this.currentRung()) {
      return;
    }
    this.durationChange.emit({
      subject: this.ban().bannedSubject,
      durationSeconds: DURATION_LADDER[clamped],
    });
  }
}
