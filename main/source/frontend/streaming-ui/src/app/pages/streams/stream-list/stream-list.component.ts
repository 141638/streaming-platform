import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterModule } from '@angular/router';
import { CardModule } from 'primeng/card';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { StreamStatusBadgeComponent } from '../../../shared/molecules/stream-status-badge/stream-status-badge.component';

@Component({
  selector: 'app-stream-list',
  standalone: true,
  imports: [
    RouterModule,
    DatePipe,
    CardModule,
    MessageModule,
    ProgressSpinnerModule,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './stream-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamListComponent {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly placeholder = 'img/stream-placeholder.svg';
  protected readonly streams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  constructor() {
    this.load();
  }

  public reload(): void {
    this.load();
  }

  /** Loads the current user's streams; kept private so callers use reload(). */
  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .listMyStreams()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.streams.set(data);
          this.loading.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to load streams. Please try again.');
          this.loading.set(false);
        },
      });
  }
}
