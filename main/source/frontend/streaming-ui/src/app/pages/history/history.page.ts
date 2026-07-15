import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterModule } from '@angular/router';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { WatchHistoryEntryDto } from '../../core/contracts/watch-history-entry.dto';
import { StreamService } from '../../core/services/stream.service';
import { StreamStatusBadgeComponent } from '../../shared/molecules/stream-status-badge/stream-status-badge.component';

@Component({
  selector: 'app-history-page',
  standalone: true,
  imports: [
    RouterModule,
    DatePipe,
    MessageModule,
    ProgressSpinnerModule,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './history.page.html',
  styleUrl: './history.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HistoryPage implements OnInit {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly placeholder = 'img/stream-placeholder.svg';

  protected readonly entries = signal<WatchHistoryEntryDto[]>([]);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  public ngOnInit(): void {
    this.streamService
      .getWatchHistory()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.entries.set(data);
          this.loading.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to load watch history.');
          this.loading.set(false);
        },
      });
  }
}
