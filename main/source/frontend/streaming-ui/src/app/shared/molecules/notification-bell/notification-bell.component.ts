import {
  ChangeDetectionStrategy,
  Component,
  inject,
  Signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BadgeModule } from 'primeng/badge';
import { ButtonModule } from 'primeng/button';
import { NotificationService } from '../../../core/services/notification.service';

@Component({
  selector: 'app-notification-bell',
  standalone: true,
  imports: [ButtonModule, BadgeModule],
  templateUrl: './notification-bell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationBellComponent {
  private readonly notificationService = inject(NotificationService);

  protected readonly unreadCount: Signal<number> = toSignal(
    this.notificationService.unreadCount$,
    { initialValue: 0 },
  );

  public onBellClick(): void {
    this.notificationService.testMock();
  }
}
