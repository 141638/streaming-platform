import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { dicebearAvatarUrl, displayName } from '../../../core/lib/avatar';

/** The subject a moderator is about to ban, plus its display username. */
interface BanTarget {
  readonly subject: string;
  readonly username: string | null;
}

/** A selectable ban duration; {@code null} value = permanent. */
interface DurationOption {
  readonly label: string;
  readonly value: number | null;
}

/** The payload emitted when the moderator confirms the ban. */
interface BanConfirmation {
  readonly reason: string | null;
  readonly durationSeconds: number | null;
}

const REASON_MAX_LENGTH = 500;
const DEFAULT_DURATION_SECONDS = 86_400;

/**
 * Modal for issuing a ban. Dumb + OnPush: the parent owns {@code visible} and
 * the {@code target}; this component only collects a reason + duration and emits
 * the confirmation. The form is reset every time the dialog re-opens so a stale
 * reason never leaks between targets.
 */
@Component({
  selector: 'app-ban-user-dialog',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    DialogModule,
    ButtonModule,
    SelectModule,
    TextareaModule,
  ],
  templateUrl: './ban-user-dialog.component.html',
  styleUrl: './ban-user-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BanUserDialogComponent {
  public readonly visible = model<boolean>(false);
  public readonly target = input<BanTarget | null>(null);

  public readonly confirm = output<BanConfirmation>();
  public readonly cancel = output<void>();

  private readonly fb = inject(FormBuilder);

  protected readonly reasonMaxLength = REASON_MAX_LENGTH;
  protected readonly durationOptions: DurationOption[] = [
    { label: '1 hour', value: 3600 },
    { label: '24 hours', value: 86_400 },
    { label: '7 days', value: 604_800 },
    { label: 'Permanent', value: null },
  ];

  protected readonly form = this.fb.group({
    reason: this.fb.control('', {
      nonNullable: true,
      validators: [Validators.maxLength(REASON_MAX_LENGTH)],
    }),
    duration: this.fb.control<number | null>(DEFAULT_DURATION_SECONDS),
  });

  protected readonly hasTarget = computed(() => this.target() !== null);
  protected readonly targetName = computed(() => {
    const target = this.target();
    return target === null ? '' : displayName(target.subject, target.username);
  });
  protected readonly targetAvatar = computed(() => {
    const target = this.target();
    return target === null ? '' : dicebearAvatarUrl(target.subject);
  });

  public constructor() {
    // Reset to a clean 24h default each time the dialog opens.
    effect(() => {
      if (this.visible()) {
        this.form.reset({
          reason: '',
          duration: DEFAULT_DURATION_SECONDS,
        });
      }
    });
  }

  // ── Public methods ─────────────────────────────────────────────────────

  protected onConfirm(): void {
    const reason = this.form.controls.reason.value.trim();
    this.confirm.emit({
      reason: reason.length > 0 ? reason : null,
      durationSeconds: this.form.controls.duration.value,
    });
  }

  protected onCancel(): void {
    this.cancel.emit();
  }
}
