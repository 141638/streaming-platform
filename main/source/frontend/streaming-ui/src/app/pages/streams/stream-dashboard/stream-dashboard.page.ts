import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { StreamStageComponent } from '../../../shared/organisms/stream-stage/stream-stage.component';
import { StreamStatusBadgeComponent } from '../../../shared/molecules/stream-status-badge/stream-status-badge.component';
import { StreamListComponent } from '../stream-list/stream-list.component';

@Component({
  selector: 'app-stream-dashboard',
  standalone: true,
  imports: [
    DatePipe,
    MessageModule,
    ProgressSpinnerModule,
    StreamStageComponent,
    StreamStatusBadgeComponent,
    StreamListComponent,
  ],
  templateUrl: './stream-dashboard.page.html',
  styleUrl: './stream-dashboard.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamDashboardPage {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  protected readonly streams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  /** The stream shown on the stage: the live one, else the most recent. */
  protected readonly currentStream = computed<
    StreamSummaryResponseDto | undefined
  >(() => {
    const all = this.streams();
    if (all.length === 0) {
      return undefined;
    }
    const live = all.find((s) => s.status.toLowerCase() === 'live');
    if (live) {
      return live;
    }
    return [...all].sort(
      (a, b) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime(),
    )[0];
  });

  constructor() {
    this.load();
  }

  /** Navigate to the stream detail page for management. */
  protected navigateToStreamDetail(streamId: string): void {
    this.router.navigate(['/channel', streamId]);
  }

  /** Loads the current user's streams; kept private so callers use the ctor. */
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
