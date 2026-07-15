import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { ChipModule } from 'primeng/chip';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { SkeletonModule } from 'primeng/skeleton';
import {
  debounceTime,
  distinctUntilChanged,
  Subject,
  switchMap,
  tap,
} from 'rxjs';
import { StreamSummaryResponseDto } from '../../core/contracts/stream-summary-response.dto';
import { StreamService } from '../../core/services/stream.service';
import { StreamStatusBadgeComponent } from '../../shared/molecules/stream-status-badge/stream-status-badge.component';
import { RailComponent } from '../../shared/molecules/rail/rail.component';
import { formatCount } from '../../shared/lib/format-count.util';

@Component({
  selector: 'app-browse-page',
  standalone: true,
  imports: [
    RouterModule,
    FormsModule,
    ChipModule,
    IconFieldModule,
    InputIconModule,
    InputTextModule,
    MessageModule,
    ProgressSpinnerModule,
    SkeletonModule,
    StreamStatusBadgeComponent,
    RailComponent,
  ],
  templateUrl: './browse.page.html',
  styleUrl: './browse.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BrowsePage implements OnInit {
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly placeholder = 'img/stream-placeholder.svg';
  protected readonly formatCount = formatCount;

  // ── Live Now rail ─────────────────────────────────────────────────────

  protected readonly liveStreams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly liveCursor = signal<string | null>(null);
  protected readonly liveLoading = signal(true);
  protected readonly liveHasMore = signal(false);

  // ── Recently Ended rail ───────────────────────────────────────────────

  protected readonly endedStreams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly endedLoading = signal(true);

  // ── Categories ────────────────────────────────────────────────────────

  protected readonly categoryNames = signal<string[]>([]);
  protected readonly selectedCategory = signal<string | null>(null);

  // ── Search ────────────────────────────────────────────────────────────

  protected readonly searchQuery = signal('');
  protected readonly errorMessage = signal<string | undefined>(undefined);

  private readonly searchSubject = new Subject<string>();
  protected readonly loadingMore = signal(false);

  protected readonly filteredLiveStreams = computed(() => {
    const cat = this.selectedCategory();
    if (!cat) return this.liveStreams();
    return this.liveStreams().filter((s) => s.category === cat);
  });

  public ngOnInit(): void {
    // Debounced search for live streams
    this.searchSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => {
          this.liveCursor.set(null);
          this.liveStreams.set([]);
          this.liveLoading.set(true);
        }),
        switchMap((q) =>
          this.streamService.getLiveStreams({
            keyword: q || undefined,
            limit: 24,
          }),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => this.applyLivePage(page),
        error: () => {
          this.errorMessage.set('Failed to load streams. Please try again.');
          this.liveLoading.set(false);
        },
      });

    // Initial load
    this.loadLive();
    this.loadRecentlyEnded();
  }

  protected onSearchChange(query: string): void {
    this.searchQuery.set(query);
    this.errorMessage.set(undefined);
    this.searchSubject.next(query);
  }

  public reload(): void {
    this.liveCursor.set(null);
    this.liveStreams.set([]);
    this.loadLive();
    this.loadRecentlyEnded();
  }

  protected loadMoreLive(): void {
    if (!this.liveHasMore() || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.streamService
      .getLiveStreams({
        keyword: this.searchQuery() || undefined,
        cursor: this.liveCursor() ?? undefined,
        limit: 24,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.appendLivePage(page);
          this.loadingMore.set(false);
        },
        error: () => this.loadingMore.set(false),
      });
  }

  protected selectCategory(cat: string): void {
    this.selectedCategory.set(
      this.selectedCategory() === cat ? null : cat,
    );
  }

  protected clearFilters(): void {
    this.searchQuery.set('');
    this.selectedCategory.set(null);
    this.searchSubject.next('');
  }

  // ── Private helpers ───────────────────────────────────────────────────

  private loadLive(): void {
    this.liveLoading.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .getLiveStreams({ limit: 24 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.applyLivePage(page),
        error: () => {
          this.errorMessage.set('Failed to load streams. Please try again.');
          this.liveLoading.set(false);
        },
      });
  }

  private loadRecentlyEnded(): void {
    this.endedLoading.set(true);
    this.streamService
      .getRecentlyEndedStreams({ hours: 24, limit: 20 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.endedStreams.set(data);
          this.endedLoading.set(false);
        },
        error: () => this.endedLoading.set(false),
      });
  }

  private applyLivePage(page: {
    streams: readonly StreamSummaryResponseDto[];
    nextCursor: string | null;
    hasMore: boolean;
  }): void {
    this.liveStreams.set([...page.streams]);
    this.liveCursor.set(page.nextCursor);
    this.liveHasMore.set(page.hasMore);
    this.liveLoading.set(false);
    this.deriveCategories(page.streams);
  }

  private appendLivePage(page: {
    streams: readonly StreamSummaryResponseDto[];
    nextCursor: string | null;
    hasMore: boolean;
  }): void {
    this.liveStreams.update((prev) => [...prev, ...page.streams]);
    this.liveCursor.set(page.nextCursor);
    this.liveHasMore.set(page.hasMore);
    this.deriveCategories(this.liveStreams());
  }

  private deriveCategories(
    items: readonly StreamSummaryResponseDto[],
  ): void {
    const seen = new Set(
      items
        .map((s) => s.category)
        .filter((c): c is string => c != null && c.length > 0),
    );
    this.categoryNames.set(Array.from(seen).sort());
  }
}
