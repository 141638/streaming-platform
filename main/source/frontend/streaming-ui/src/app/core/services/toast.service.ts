import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { NotificationDto, ToastDto } from '../contracts/notification.dto';

@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly toastSubject = new Subject<ToastDto>();

  public readonly toast$ = this.toastSubject.asObservable();

  /** Generic push — for callers that build their own ToastDto. */
  public show(dto: ToastDto): void {
    this.toastSubject.next(dto);
  }

  /** Build a toast envelope from a NotificationDto (the primary code path). */
  public showNotification(notification: NotificationDto): void {
    this.show({
      severity: notification.severity,
      summary: notification.title,
      detail: notification.message,
      data: notification,
    });
  }

  public showSuccess(summary: string, detail?: string): void {
    this.show({ severity: 'success', summary, detail: detail ?? '' });
  }

  public showInfo(summary: string, detail?: string): void {
    this.show({ severity: 'info', summary, detail: detail ?? '' });
  }

  public showError(summary: string, detail?: string): void {
    this.show({ severity: 'error', summary, detail: detail ?? '' });
  }

  public showWarn(summary: string, detail?: string): void {
    this.show({ severity: 'warn', summary, detail: detail ?? '' });
  }

  /** Signal the toast host to remove all visible toasts. */
  public clear(): void {
    // An empty-severity message is the convention for "clear all".
    this.toastSubject.next({ severity: 'info' as never, summary: '', detail: '' });
  }
}
