import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { dicebearAvatarUrl, displayName } from '../../../core/lib/avatar';
import { formatExpiresIn } from '../../../core/lib/time';

/** Absolute preset ladder the inline editor steps through, ascending; top = permanent. */
const DURATION_LADDER: readonly (number | null)[] = [3600, 86_400, 604_800, null];
/** Human labels parallel to {@link DURATION_LADDER}, shown while staging a change. */
const DURATION_LADDER_LABELS: readonly string[] = [
  '1 hour',
  '24 hours',
  '7 days',
  'Permanent',
];
const PERMANENT_RUNG = DURATION_LADDER.length - 1;

/** Payload emitted when a moderator steps a ban's duration up or down. */
export interface DurationChange {
  readonly subject: string;
  readonly durationSeconds: number | null;
}

/**
 * One roster row for the moderation ban list. Dumb + OnPush: it renders a single
 * {@link BanResponseDto}, emits the subject when the moderator lifts the ban, and
 * emits a {@link DurationChange} when they **apply** a staged duration edit on the
 * preset ladder (1h → 24h → 7d → permanent). Stepping only stages a local pending
 * rung; a single change is emitted on apply, so ratcheting across several rungs is
 * one change (and, in Wave 2, one notification) rather than one per click. The live
 * "expires in" copy is driven by the {@code nowMs} tick from the parent, so the row
 * never owns a timer of its own.
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
  /**
   * The rung the moderator is editing toward. Seeded from {@link currentRung} and
   * re-seeded whenever the underlying ban changes (e.g. after a successful apply
   * round-trips the new expiry), but freely writable while stepping — so
   * intermediate rungs never leave the client until the moderator applies.
   */
  protected readonly pendingRung = linkedSignal(() => this.currentRung());
  protected readonly isDirty = computed(
    () => this.pendingRung() !== this.currentRung(),
  );
  protected readonly pendingDurationLabel = computed(
    () => DURATION_LADDER_LABELS[this.pendingRung()],
  );
  protected readonly pendingIsPermanent = computed(
    () => this.pendingRung() === PERMANENT_RUNG,
  );
  protected readonly canStepUp = computed(
    () => this.pendingRung() < PERMANENT_RUNG,
  );
  protected readonly canStepDown = computed(() => this.pendingRung() > 0);

  // ── Public methods ─────────────────────────────────────────────────────

  protected onUnban(): void {
    this.unban.emit(this.ban().bannedSubject);
  }

  /** Stage one rung longer — local only, no emit until apply. */
  protected onStepLonger(): void {
    this.stepTo(this.pendingRung() + 1);
  }

  /** Stage one rung shorter — local only, no emit until apply. */
  protected onStepShorter(): void {
    this.stepTo(this.pendingRung() - 1);
  }

  /**
   * Commit the staged duration as a single change. Collapsing several steps into
   * one emit here is the whole point: it keeps a future ban-change notification
   * (Wave 2) from firing once per intermediate rung.
   */
  protected onApply(): void {
    if (!this.isDirty()) {
      return;
    }
    this.durationChange.emit({
      subject: this.ban().bannedSubject,
      durationSeconds: DURATION_LADDER[this.pendingRung()],
    });
  }

  /** Discard the staged change, snapping back to the committed rung. */
  protected onRevert(): void {
    this.pendingRung.set(this.currentRung());
  }

  // ── Private methods ─────────────────────────────────────────────────────

  /** Stage a clamped ladder index locally; the emit happens on {@link onApply}. */
  private stepTo(rung: number): void {
    this.pendingRung.set(Math.max(0, Math.min(PERMANENT_RUNG, rung)));
  }
}
