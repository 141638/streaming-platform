import { Component, DestroyRef, inject, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ToastModule } from 'primeng/toast';
import { ToastDto } from '../../../core/contracts/notification.dto';
import { playNotificationSound } from '../../../core/lib/notification-sound';
import { ToastService } from '../../../core/services/toast.service';
import { NotificationCardComponent } from '../notification-card/notification-card.component';

@Component({
  selector: 'app-notification-toast',
  standalone: true,
  imports: [ToastModule, NotificationCardComponent],
  templateUrl: './notification-toast.component.html',
  providers: [MessageService],
})
export class NotificationToastComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly toastService = inject(ToastService);
  protected readonly messageService = inject(MessageService);
  private readonly router = inject(Router);

  public ngOnInit(): void {
    this.toastService.toast$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((dto: ToastDto) => {
        // Empty summary = clear-all signal from ToastService.clear().
        if (!dto.summary && !dto.detail && !dto.data) {
          this.messageService.clear();
          return;
        }
        this.messageService.add({
          severity: dto.severity,
          summary: dto.summary,
          detail: dto.detail,
          data: dto.data,
          life: 6000,
        });
        playNotificationSound();
      });
  }

  /** Called when a clickable notification card is clicked. Navigate + dismiss. */
  public onCardClick(dto: ToastDto): void {
    const route = dto.data?.clickAction?.route;
    if (route) {
      this.router.navigateByUrl(route);
    }
    this.messageService.clear();
  }
}
