import { Component, effect, inject, OnDestroy } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { NotificationService } from './core/services/notification.service';
import { NotificationToastComponent } from './shared/molecules/notification-toast/notification-toast.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, NotificationToastComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnDestroy {
  protected readonly title = 'streaming-ui';

  private readonly authService = inject(AuthService);
  private readonly notificationService = inject(NotificationService);

  constructor() {
    effect(() => {
      if (this.authService.isAuthenticated()) {
        this.notificationService.connect();
        this.notificationService.refreshUnreadCount().subscribe({
          error: () => {
            // REST fetch failed — global interceptor handles the UX;
            // unread count stays at its previous value.
          },
        });
      } else {
        this.notificationService.disconnect();
      }
    });
  }

  public ngOnDestroy(): void {
    this.notificationService.disconnect();
  }
}
