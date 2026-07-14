import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgClass } from '@angular/common';
import { NotificationDto } from '../../../core/contracts/notification.dto';
import { formatRelativeTime } from '../../../core/lib/time';
import { UserProfilePictureComponent } from '../../atoms/user-profile-picture/user-profile-picture.component';

@Component({
  selector: 'app-notification-card',
  standalone: true,
  imports: [NgClass, UserProfilePictureComponent],
  templateUrl: './notification-card.component.html',
  styleUrl: './notification-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationCardComponent {
  public readonly notification = input.required<NotificationDto>();
  /** Emitted when the user clicks the card body (only when actionable). */
  public readonly cardClick = output<NotificationDto>();

  /** Whether the card as a whole behaves as a click target. */
  public readonly isClickable = computed<boolean>(() => {
    const a = this.notification().clickAction;
    return !!a && a.type !== 'none';
  });

  /** Severity-appropriate PrimeNG icon class. */
  public readonly severityIcon = computed<string>(() => {
    const map: Record<string, string> = {
      success: 'pi pi-check-circle',
      info: 'pi pi-info-circle',
      warn: 'pi pi-exclamation-triangle',
      error: 'pi pi-times-circle',
    };
    return map[this.notification().severity] ?? 'pi pi-info-circle';
  });

  /** Full ngClass object — severity accent bar + optional clickable cursor. */
  public readonly cardClasses = computed<Record<string, boolean>>(() => ({
    [`notification-card--${this.notification().severity}`]: true,
    'notification-card--clickable': this.isClickable(),
  }));

  /** Relative display label e.g. "now", "2h ago", "3d ago". */
  public readonly relativeTime = computed<string>(() => {
    return formatRelativeTime(this.notification().createdAt, Date.now());
  });

  /** Full ISO timestamp shown on hover. */
  public readonly fullTimestamp = computed<string>(() => {
    try {
      const d = new Date(this.notification().createdAt);
      return d.toLocaleString();
    } catch {
      return this.notification().createdAt;
    }
  });
}
