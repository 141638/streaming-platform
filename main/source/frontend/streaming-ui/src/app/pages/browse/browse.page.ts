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

  protected readonly streams = signal<StreamSummaryResponseDto[]>([]);
  protected readonly categoryNames = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadingMore = signal(false);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  protected readonly searchQuery = signal('');
  protected readonly selectedCategory = signal<string | null>(null);

  private readonly searchSubject = new Subject<string>();
  private cursor: string | null = null;
  protected readonly hasMore = signal(false);

  protected readonly filteredStreams = computed(() => {
    const cat = this.selectedCategory();
    if (!cat) return this.streams();
    return this.streams().filter((s) => s.category === cat);
  });

  public ngOnInit(): void {
    // Debounced search: 300ms delay, distinct until changed
    this.searchSubject
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap(() => {
          this.cursor = null;
          this.streams.set([]);
          this.loading.set(true);
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
        next: (page) => this.applyPage(page),
        error: () => {
          this.errorMessage.set('Failed to load streams. Please try again.');
          this.loading.set(false);
        },
      });

    // Initial load
    this.load();
  }

  protected onSearchChange(query: string): void {
    this.searchQuery.set(query);
    this.errorMessage.set(undefined);
    this.searchSubject.next(query);
  }

  public reload(): void {
    this.cursor = null;
    this.streams.set([]);
    this.load();
  }

  protected loadMore(): void {
    if (!this.hasMore() || this.loadingMore()) return;
    this.loadingMore.set(true);
    this.streamService
      .getLiveStreams({
        keyword: this.searchQuery() || undefined,
        cursor: this.cursor ?? undefined,
        limit: 24,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.appendPage(page);
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

  private load(): void {
    this.loading.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .getLiveStreams({ limit: 24 })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => this.applyPage(page),
        error: () => {
          this.errorMessage.set('Failed to load streams. Please try again.');
          this.loading.set(false);
        },
      });
  }

  private applyPage(page: {
    streams: readonly StreamSummaryResponseDto[];
    nextCursor: string | null;
    hasMore: boolean;
  }): void {
    this.streams.set([...page.streams]);
    this.cursor = page.nextCursor;
    this.hasMore.set(page.hasMore);
    this.loading.set(false);
    this.deriveCategories(page.streams);
  }

  private appendPage(page: {
    streams: readonly StreamSummaryResponseDto[];
    nextCursor: string | null;
    hasMore: boolean;
  }): void {
    this.streams.update((prev) => [...prev, ...page.streams]);
    this.cursor = page.nextCursor;
    this.hasMore.set(page.hasMore);
    this.deriveCategories(this.streams());
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
