import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { NotificationService } from '../../../core/services/notification.service';

@Component({
  selector: 'app-notification-bell',
  standalone: true,
  imports: [ButtonModule],
  templateUrl: './notification-bell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationBellComponent {
  private readonly notificationService = inject(NotificationService);

  /**
   * Trigger the mock notification pipeline so we can visually verify the
   * toast + card molecule + sound end-to-end without SSE.
   *
   * Wave 2: replace with a menu toggle when the bell has real data.
   */
  public onBellClick(): void {
    this.notificationService.testMock();
  }
}
