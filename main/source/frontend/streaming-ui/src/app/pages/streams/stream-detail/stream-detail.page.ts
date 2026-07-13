import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { PanelModule } from 'primeng/panel';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { PublishKeyResponseDto } from '../../../core/contracts/publish-key-response.dto';
import { StreamResponseDto } from '../../../core/contracts/stream-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { StreamStageComponent } from '../../../shared/organisms/stream-stage/stream-stage.component';
import { StreamStatusBadgeComponent } from '../../../shared/molecules/stream-status-badge/stream-status-badge.component';

@Component({
  selector: 'app-stream-detail',
  standalone: true,
  imports: [
    DatePipe,
    ButtonModule,
    CardModule,
    InputTextModule,
    MessageModule,
    PanelModule,
    ProgressSpinnerModule,
    StreamStageComponent,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './stream-detail.page.html',
  styleUrl: './stream-detail.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamDetailPage implements OnInit {
  public readonly id = input.required<string>();

  private readonly streamService = inject(StreamService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly placeholder = 'img/stream-placeholder.svg';
  protected readonly stream = signal<StreamResponseDto | undefined>(undefined);
  protected readonly loading = signal(true);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  protected readonly publishKey = signal<PublishKeyResponseDto | undefined>(
    undefined,
  );
  protected readonly keyLoading = signal(false);
  protected readonly tokenRevealed = signal(false);
  protected readonly copied = signal(false);
  protected readonly actionInProgress = signal(false);

  /** Status normalized to upper-case so gates tolerate the API's wire casing. */
  private readonly statusUpper = computed(
    () => this.stream()?.status?.toUpperCase() ?? '',
  );

  protected readonly canStart = computed(() => this.statusUpper() === 'DRAFT');
  protected readonly canGoLive = computed(
    () => this.statusUpper() === 'SCHEDULED',
  );
  protected readonly canEnd = computed(() => this.statusUpper() === 'LIVE');
  protected readonly canCancel = computed(() => {
    const status = this.statusUpper();
    return status === 'DRAFT' || status === 'SCHEDULED';
  });

  protected readonly canArchive = computed(() => {
    const s = this.stream();
    return s !== undefined
      && s.status.toUpperCase() === 'ENDED'
      && (s.archivedUrl === null || s.archivedUrl === undefined);
  });

  /** Whether the stream has been archived (archivedUrl is set). */
  protected readonly isArchived = computed(() => {
    const s = this.stream();
    return s?.archivedUrl != null;
  });

  /** Play URL for the stage; the shell ignores it but the Phase-4 player uses it. */
  protected readonly playUrl = computed(() => this.publishKey()?.playUrl ?? null);

  public ngOnInit(): void {
    this.loadStream();
  }

  public onStart(): void {
    this.runLifecycle(this.streamService.startStream(this.id()));
  }

  public onGoLive(): void {
    this.runLifecycle(this.streamService.goLive(this.id()));
  }

  public onEnd(): void {
    this.runLifecycle(this.streamService.endStream(this.id()));
  }

  public onCancel(): void {
    this.runLifecycle(this.streamService.cancelStream(this.id()));
  }

  public onArchive(): void {
    if (this.actionInProgress()) return;
    this.actionInProgress.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .archiveStream(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.stream.set(data);
          this.actionInProgress.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to archive stream.');
          this.actionInProgress.set(false);
        },
      });
  }

  public onGenerateKey(): void {
    if (this.keyLoading()) {
      return;
    }
    this.keyLoading.set(true);
    this.tokenRevealed.set(false);

    this.streamService
      .issuePublishKey(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (key) => {
          this.publishKey.set(key);
          this.tokenRevealed.set(true);
          this.keyLoading.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to generate publish key.');
          this.keyLoading.set(false);
        },
      });
  }

  public onToggleReveal(): void {
    this.tokenRevealed.update((revealed) => !revealed);
  }

  public onCopyRtmp(): void {
    const key = this.publishKey();
    if (!key) {
      return;
    }
    navigator.clipboard.writeText(key.rtmpUrl).then(
      () => {
        this.copied.set(true);
        setTimeout(() => this.copied.set(false), 2000);
      },
      () => this.errorMessage.set('Copy failed. Copy the URL manually.'),
    );
  }

  public onBack(): void {
    this.router.navigateByUrl('/dashboard/streams');
  }

  /** Loads the stream, then its publish key if the status can have one. */
  private loadStream(): void {
    this.loading.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .getStream(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.stream.set(data);
          this.loading.set(false);
          this.loadPublishKey();
        },
        error: () => {
          this.errorMessage.set('Failed to load stream.');
          this.loading.set(false);
        },
      });
  }

  /** Fetches the existing masked key; a 404 simply means none issued yet. */
  private loadPublishKey(): void {
    this.streamService
      .getPublishKey(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (key) => this.publishKey.set(key),
        error: () => this.publishKey.set(undefined),
      });
  }

  /** Runs a lifecycle transition, then refreshes stream + key state. */
  private runLifecycle(
    action: ReturnType<StreamService['startStream']>,
  ): void {
    if (this.actionInProgress()) {
      return;
    }
    this.actionInProgress.set(true);
    this.errorMessage.set(undefined);

    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.stream.set(data);
        this.actionInProgress.set(false);
        this.loadPublishKey();
      },
      error: () => {
        this.errorMessage.set('Action failed. Please try again.');
        this.actionInProgress.set(false);
      },
    });
  }
}
