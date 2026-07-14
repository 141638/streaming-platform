import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import Hls from 'hls.js';
import { WatchResponseDto } from '../../core/contracts/watch-response.dto';
import { PresenceService } from '../../core/services/presence.service';
import { StreamService } from '../../core/services/stream.service';
import { StreamChatShellComponent } from '../../shared/molecules/stream-chat-shell/stream-chat-shell.component';
import { StreamStatusBadgeComponent } from '../../shared/molecules/stream-status-badge/stream-status-badge.component';

@Component({
  selector: 'app-watch-page',
  standalone: true,
  imports: [
    RouterModule,
    MessageModule,
    ProgressSpinnerModule,
    StreamChatShellComponent,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './watch.page.html',
  styleUrl: './watch.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WatchPage implements OnInit {
  private readonly streamService = inject(StreamService);
  private readonly presenceService = inject(PresenceService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly videoEl =
    viewChild<ElementRef<HTMLVideoElement>>('videoPlayer');

  protected readonly data = signal<WatchResponseDto | null>(null);
  protected readonly loading = signal(true);
  protected readonly errorCode = signal<string | undefined>(undefined);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  private hls: Hls | null = null;
  private hlsStarted = false;

  public ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errorCode.set('INVALID_ROUTE');
      this.errorMessage.set('No stream ID in URL.');
      this.loading.set(false);
      return;
    }

    this.streamService
      .getWatchData(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (d) => {
          this.data.set(d);
          this.loading.set(false);
          this.presenceService.start(id);
        },
        error: (err: unknown) => {
          this.loading.set(false);
          if (err instanceof HttpErrorResponse) {
            if (err.status === 404) {
              this.errorCode.set('STREAM_NOT_FOUND');
              this.errorMessage.set('This stream does not exist.');
            } else if (err.status === 409) {
              this.errorCode.set('STREAM_NOT_LIVE');
              this.errorMessage.set('This stream is not currently live.');
            } else {
              this.errorCode.set('LOAD_FAILED');
              this.errorMessage.set(
                'Failed to load stream data. Please try again.',
              );
            }
          } else {
            this.errorCode.set('LOAD_FAILED');
            this.errorMessage.set(
              'Failed to load stream data. Please try again.',
            );
          }
        },
      });

    // Start HLS when data arrives and the video element is ready.
    // Guarded with hlsStarted to prevent re-initialization on signal changes.
    effect(() => {
      const d = this.data();
      const el = this.videoEl();
      if (d && el && !this.hlsStarted) {
        this.hlsStarted = true;
        this.startHls(d.playUrl, el.nativeElement);
      }
    });
  }

  public ngOnDestroy(): void {
    this.destroyHls();
    this.presenceService.stop();
  }

  private startHls(url: string, video: HTMLVideoElement): void {
    if (Hls.isSupported()) {
      this.hls = new Hls();
      this.hls.loadSource(url);
      this.hls.attachMedia(video);
      this.hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(() => {
          // Autoplay may be blocked — user can tap play
        });
      });
      this.hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) {
          switch (data.type) {
            case Hls.ErrorTypes.NETWORK_ERROR:
              this.hls?.startLoad();
              break;
            case Hls.ErrorTypes.MEDIA_ERROR:
              this.hls?.recoverMediaError();
              break;
            default:
              this.destroyHls();
              break;
          }
        }
      });
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Native HLS support (Safari)
      video.src = url;
    }
  }

  private destroyHls(): void {
    this.hls?.destroy();
    this.hls = null;
    this.hlsStarted = false;
  }
}
