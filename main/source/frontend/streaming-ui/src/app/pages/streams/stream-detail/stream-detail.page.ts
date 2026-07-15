import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { PanelModule } from 'primeng/panel';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { ToggleButtonModule } from 'primeng/togglebutton';
import { PublishKeyResponseDto } from '../../../core/contracts/publish-key-response.dto';
import { StreamResponseDto } from '../../../core/contracts/stream-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { StreamSseService } from '../../../core/services/stream-sse.service';
import { StreamStageComponent } from '../../../shared/organisms/stream-stage/stream-stage.component';
import { StreamStatusBadgeComponent } from '../../../shared/molecules/stream-status-badge/stream-status-badge.component';

@Component({
  selector: 'app-stream-detail',
  standalone: true,
  imports: [
    DatePipe,
    FormsModule,
    ButtonModule,
    CardModule,
    InputTextModule,
    MessageModule,
    PanelModule,
    ProgressSpinnerModule,
    ToggleButtonModule,
    StreamStageComponent,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './stream-detail.page.html',
  styleUrl: './stream-detail.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamDetailPage implements OnInit, OnDestroy {
  public readonly id = input.required<string>();

  private readonly streamService = inject(StreamService);
  private readonly streamSseService = inject(StreamSseService);
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
  protected readonly serverCopied = signal(false);
  protected readonly streamKeyCopied = signal(false);
  protected readonly actionInProgress = signal(false);

  /** Cancel confirmation dialog visibility. */
  protected readonly showCancelConfirm = signal(false);

  /** Status normalized to upper-case so gates tolerate the API's wire casing. */
  private readonly statusUpper = computed(
    () => this.stream()?.status?.toUpperCase() ?? '',
  );

  protected readonly canGoLive = computed(
    () => this.statusUpper() === 'SCHEDULED',
  );
  protected readonly canEnd = computed(() => this.statusUpper() === 'LIVE');
  protected readonly canCancel = computed(() => {
    const status = this.statusUpper();
    return status === 'DRAFT' || status === 'SCHEDULED';
  });

  /** Whether the chat archive settings section should be visible. */
  protected readonly showArchiveSettings = computed(() => {
    const status = this.statusUpper();
    return status === 'DRAFT' || status === 'LIVE';
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

  /** RTMP server base URL (OBS Settings → Stream → Server). */
  protected readonly rtmpServerUrl = computed(() => {
    const key = this.publishKey();
    if (!key) return '';
    // The rtmpUrl is "{host}/live/{srsName}?token={token}"
    // Extract everything before "/live/" + the "/live" app path
    const url = key.rtmpUrl;
    const liveIdx = url.indexOf('/live/');
    return liveIdx > 0 ? url.substring(0, liveIdx + 5) : url;
  });

  /** Full stream key for OBS: "{srsName}?token={token}". */
  protected readonly streamKey = computed(() => {
    const key = this.publishKey();
    if (!key) return '';
    return `${key.srsName}?token=${key.token}`;
  });

  /** Masked stream key for display when token is hidden. */
  protected readonly maskStreamKey = computed(() => {
    const key = this.publishKey();
    if (!key) return '';
    return `${key.srsName}?token=****`;
  });

  public ngOnInit(): void {
    // Check for publish key passed via navigation state (from stream creation)
    const navState = this.router.lastSuccessfulNavigation?.extras?.state as
      { publishKey?: PublishKeyResponseDto } | undefined;
    if (navState?.publishKey) {
      this.publishKey.set(navState.publishKey);
      this.tokenRevealed.set(true);
    }
    this.loadStream();

    // Subscribe to SSE stream lifecycle events for this stream
    this.streamSseService.connect();
    this.streamSseService.streamStarted$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((event) => {
        if (event.streamId === this.id()) {
          this.loadStream();
        }
      });
  }

  public ngOnDestroy(): void {
    this.streamSseService.disconnect();
  }

  /**
   * SCHEDULED → DRAFT with a fresh publish key.
   */
  public onGoLive(): void {
    if (this.actionInProgress()) return;
    this.actionInProgress.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .goLive(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (key) => {
          this.publishKey.set(key);
          this.tokenRevealed.set(true);
          this.actionInProgress.set(false);
          this.loadStream();
        },
        error: () => {
          this.errorMessage.set('Action failed. Please try again.');
          this.actionInProgress.set(false);
        },
      });
  }

  public onEnd(): void {
    this.runLifecycle(this.streamService.endStream(this.id()));
  }

  /** Opens the cancel confirmation dialog. */
  public onCancelClick(): void {
    this.showCancelConfirm.set(true);
  }

  /** Confirms cancellation — performs the terminal DRAFT/SCHEDULED → CANCELLED transition. */
  public onCancelConfirm(): void {
    this.showCancelConfirm.set(false);
    if (this.actionInProgress()) return;
    this.actionInProgress.set(true);
    this.errorMessage.set(undefined);

    this.streamService
      .cancelStream(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.stream.set(data);
          this.actionInProgress.set(false);
        },
        error: () => {
          this.errorMessage.set('Failed to cancel stream.');
          this.actionInProgress.set(false);
        },
      });
  }

  /** Dismisses the cancel confirmation without acting. */
  public onCancelDismiss(): void {
    this.showCancelConfirm.set(false);
  }

  /** Toggle auto-archive chat setting for this stream. */
  public toggleAutoArchive(enabled: boolean): void {
    this.streamService
      .updateStream(this.id(), { autoArchiveChat: enabled } as Partial<StreamResponseDto>)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.stream.set(data),
        error: () => this.errorMessage.set('Failed to update archive settings.'),
      });
  }

  /** Update the chat archive delay for this stream. */
  public updateArchiveDelay(delay: number): void {
    this.stream.update((s) =>
      s ? { ...s, chatArchiveDelayMinutes: delay } : undefined,
    );
    this.streamService
      .updateStream(this.id(), { chatArchiveDelayMinutes: delay } as Partial<StreamResponseDto>)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.stream.set(data),
        error: () => this.errorMessage.set('Failed to update archive delay.'),
      });
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

  public onCopyServer(): void {
    const url = this.rtmpServerUrl();
    if (!url) return;
    navigator.clipboard.writeText(url).then(
      () => {
        this.serverCopied.set(true);
        setTimeout(() => this.serverCopied.set(false), 2000);
      },
      () => this.errorMessage.set('Copy failed. Copy the URL manually.'),
    );
  }

  public onCopyStreamKey(): void {
    const key = this.streamKey();
    if (!key) return;
    navigator.clipboard.writeText(key).then(
      () => {
        this.streamKeyCopied.set(true);
        setTimeout(() => this.streamKeyCopied.set(false), 2000);
      },
      () => this.errorMessage.set('Copy failed. Copy the key manually.'),
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
    // Skip if already set — preserves raw key from navigation state or goLive/generateKey
    if (this.publishKey()) {
      return;
    }
    this.streamService
      .getPublishKey(this.id())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (key) => this.publishKey.set(key),
        error: () => this.publishKey.set(undefined),
      });
  }

  /** Runs a lifecycle transition that returns StreamResponseDto, then refreshes state. */
  private runLifecycle(
    action: ReturnType<StreamService['endStream']>,
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
