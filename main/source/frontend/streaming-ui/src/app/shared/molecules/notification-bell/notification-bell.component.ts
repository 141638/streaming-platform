import {
  ChangeDetectionStrategy,
  Component,
  inject,
  Signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { BadgeModule } from 'primeng/badge';
import { ButtonModule } from 'primeng/button';
import { OverlayPanelModule } from 'primeng/overlaypanel';
import { NotificationService } from '../../../core/services/notification.service';
import { NotificationDropdownComponent } from './notification-dropdown.component';
import { Popover } from "primeng/popover";

@Component({
  selector: 'app-notification-bell',
  standalone: true,
  imports: [ButtonModule, BadgeModule, OverlayPanelModule, NotificationDropdownComponent, Popover],
  templateUrl: './notification-bell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationBellComponent {
  private readonly notificationService = inject(NotificationService);

  protected readonly unreadCount: Signal<number> = toSignal(
    this.notificationService.unreadCount$,
    { initialValue: 0 },
  );
}
