import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { SkeletonModule } from 'primeng/skeleton';
import { finalize } from 'rxjs';
import { BroadcastPageResponseDto } from '../../../core/contracts/broadcast-page-response.dto';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { RailComponent } from '../../../shared/molecules/rail/rail.component';
import { StreamStatusBadgeComponent } from '../../../shared/molecules/stream-status-badge/stream-status-badge.component';
import {
  MOCK_PLAYLISTS,
  MOCK_UPLOAD_VIDEOS,
  PlaylistCardMock,
  VideoCardMock,
} from './video-tab.mocks';

type VideoType = 'archived' | 'upload' | null;
type SortOption = 'date' | 'popular';

interface FilterOption {
  readonly label: string;
  readonly value: VideoType;
}

const FILTER_OPTIONS: readonly FilterOption[] = [
  { label: 'Archived Broadcasts', value: 'archived' },
  { label: 'Uploaded Videos', value: 'upload' },
];

interface SortItem {
  readonly label: string;
  readonly value: SortOption;
}

const SORT_OPTIONS: readonly SortItem[] = [
  { label: 'Date', value: 'date' },
  { label: 'Popular', value: 'popular' },
];

@Component({
  selector: 'app-channel-video-tab',
  standalone: true,
  imports: [
    FormsModule,
    RouterModule,
    DatePipe,
    ButtonModule,
    InputTextModule,
    SelectModule,
    SkeletonModule,
    RailComponent,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './video-tab.component.html',
  styleUrl: './video-tab.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class VideoTabComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly streamService = inject(StreamService);

  protected readonly username = this.route.parent!.snapshot.paramMap.get('username')!;

  // ── State ───────────────────────────────────────────────────────────────

  protected readonly isLoading = signal(true);
  protected readonly broadcasts = signal<StreamSummaryResponseDto[]>([]);
  protected readonly pageResponse = signal<BroadcastPageResponseDto | null>(null);

  protected readonly typeFilter = signal<VideoType>(null);
  protected readonly searchKeyword = signal('');
  protected readonly sortBy = signal<SortOption>('date');
  protected readonly currentPage = signal(0);
  protected readonly pageSize = signal(24);

  protected readonly filterOptions: FilterOption[] = [...FILTER_OPTIONS];
  protected readonly sortOptions: SortItem[] = [...SORT_OPTIONS];

  // ── Mock deferred data ──────────────────────────────────────────────────

  protected readonly mockUploads: readonly VideoCardMock[] = MOCK_UPLOAD_VIDEOS;
  protected readonly mockPlaylists: readonly PlaylistCardMock[] = MOCK_PLAYLISTS;

  // ── Derived ─────────────────────────────────────────────────────────────

  /** In filtered grid mode when a type filter is active. */
  protected readonly isFiltered = computed(() => this.typeFilter() !== null);

  /** Show broadcast grid only for archived type. Upload is deferred. */
  protected readonly showBroadcastGrid = computed(
    () => this.typeFilter() === 'archived',
  );

  protected readonly totalPages = computed(() => {
    const meta = this.pageResponse()?.meta;
    if (!meta || meta.size === 0) return 1;
    return Math.ceil(meta.total / meta.size);
  });

  // ── Lifecycle ───────────────────────────────────────────────────────────

  public ngOnInit(): void {
    this.loadRecentBroadcasts();

    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const type = params.get('type') as VideoType;
        this.typeFilter.set(type);
        if (type) {
          this.searchKeyword.set(params.get('keyword') ?? '');
          this.sortBy.set(
            (params.get('sort') as SortOption) ?? 'date',
          );
          this.currentPage.set(Number(params.get('page') ?? 0));
          if (type === 'archived') {
            this.loadBroadcasts();
          }
        }
      });
  }

  // ── Data ────────────────────────────────────────────────────────────────

  private loadRecentBroadcasts(): void {
    this.isLoading.set(true);
    this.streamService
      .getRecentBroadcasts(this.username)
      .pipe(
        finalize(() => this.isLoading.set(false)),
      )
      .subscribe({
        next: (data) => this.broadcasts.set(data),
        error: (err: unknown) => {
          if (err instanceof DOMException && err.name === 'AbortError') return;
          this.broadcasts.set([]);
        },
      });
  }

  private loadBroadcasts(): void {
    this.isLoading.set(true);
    const sortParam =
      this.sortBy() === 'popular' ? 'views' : 'created_at';
    this.streamService
      .getBroadcasts(this.username, {
        keyword: this.searchKeyword() || undefined,
        sort: sortParam,
        order: 'desc',
        page: this.currentPage(),
        size: this.pageSize(),
      })
      .pipe(
        finalize(() => this.isLoading.set(false)),
      )
      .subscribe({
        next: (res) => this.pageResponse.set(res),
        error: (err: unknown) => {
          if (err instanceof DOMException && err.name === 'AbortError') return;
          this.pageResponse.set(null);
        },
      });
  }

  // ── User actions ────────────────────────────────────────────────────────

  protected onTypeFilterChange(value: VideoType): void {
    this.typeFilter.set(value);
    this.currentPage.set(0);
    this.updateQueryParams();
    if (value === 'archived') {
      this.loadBroadcasts();
    }
  }

  protected onSortChange(_value: SortOption): void {
    this.currentPage.set(0);
    this.updateQueryParams();
    if (this.typeFilter() === 'archived') {
      this.loadBroadcasts();
    }
  }

  protected onSearch(): void {
    this.currentPage.set(0);
    this.updateQueryParams();
    if (this.typeFilter() === 'archived') {
      this.loadBroadcasts();
    }
  }

  protected goToPage(page: number): void {
    if (page < 0 || page >= this.totalPages()) return;
    this.currentPage.set(page);
    this.updateQueryParams();
    if (this.typeFilter() === 'archived') {
      this.loadBroadcasts();
    }
  }

  protected clearFilter(): void {
    this.typeFilter.set(null);
    this.searchKeyword.set('');
    this.currentPage.set(0);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {},
      replaceUrl: true,
    });
  }

  private updateQueryParams(): void {
    const params: Record<string, string> = {};
    const type = this.typeFilter();
    if (type) params['type'] = type;
    const kw = this.searchKeyword().trim();
    if (kw) params['keyword'] = kw;
    if (this.sortBy() !== 'date') params['sort'] = this.sortBy();
    if (this.currentPage() > 0)
      params['page'] = String(this.currentPage());
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: params,
      replaceUrl: true,
    });
  }

  // ── Display helpers ─────────────────────────────────────────────────────

  protected thumbnailUrl(session: StreamSummaryResponseDto): string {
    return session.thumbnailUrl ?? 'img/stream-placeholder.svg';
  }

  protected formatCount(n: number): string {
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return String(n);
  }
}
