import { CommonModule } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CardModule } from 'primeng/card';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { MessageModule } from 'primeng/message';
import { StreamService } from '../../../core/services/stream.service';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';

@Component({
  selector: 'app-channel',
  standalone: true,
  imports: [CommonModule, CardModule, ProgressSpinnerModule, MessageModule],
  templateUrl: './channel.page.html',
})
export class ChannelPage {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly streams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  constructor() {
    this.streamService
      .listMyStreams()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.streams.set(data);
          this.loading.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to load streams.');
          this.loading.set(false);
        },
      });
  }
}
