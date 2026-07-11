import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { dicebearAvatarUrl, truncateSub } from '../../../core/lib/avatar';
import { formatExpiresIn } from '../../../core/lib/time';

type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary';

/**
 * One roster row for the moderation ban list. Dumb + OnPush: it renders a
 * single {@link BanResponseDto} and emits the banned subject when the moderator
 * lifts the ban. The live "expires in" copy is driven by the {@code nowMs} tick
 * supplied by the parent, so the row never owns a timer of its own.
 */
@Component({
  selector: 'app-ban-list-item',
  standalone: true,
  imports: [ButtonModule, TagModule],
  templateUrl: './ban-list-item.component.html',
  styleUrl: './ban-list-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BanListItemComponent {
  public readonly ban = input.required<BanResponseDto>();
  public readonly nowMs = input.required<number>();

  public readonly unban = output<string>();

  protected readonly avatarUrl = computed(() =>
    dicebearAvatarUrl(this.ban().bannedSubject),
  );
  protected readonly subjectLabel = computed(() =>
    truncateSub(this.ban().bannedSubject),
  );
  protected readonly reasonText = computed(
    () => this.ban().reason ?? 'No reason given',
  );
  protected readonly bannedByLabel = computed(
    () => `by ${truncateSub(this.ban().bannedBySubject)}`,
  );
  protected readonly isPermanent = computed(() => this.ban().expiresAt === null);
  protected readonly durationLabel = computed(() => {
    const expiresAt = this.ban().expiresAt;
    return expiresAt === null
      ? 'Permanent'
      : formatExpiresIn(expiresAt, this.nowMs());
  });
  protected readonly durationSeverity = computed<TagSeverity>(() =>
    this.isPermanent() ? 'danger' : 'warn',
  );
  protected readonly durationIcon = computed(() =>
    this.isPermanent() ? 'pi pi-ban' : 'pi pi-clock',
  );

  // ── Public methods ─────────────────────────────────────────────────────

  protected onUnban(): void {
    this.unban.emit(this.ban().bannedSubject);
  }
}
