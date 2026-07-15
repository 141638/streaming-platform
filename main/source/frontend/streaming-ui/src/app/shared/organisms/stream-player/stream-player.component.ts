import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnDestroy,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  viewChild,
} from '@angular/core';
import Hls from 'hls.js';
import { StreamStatusBadgeComponent } from '../../molecules/stream-status-badge/stream-status-badge.component';

/** Minimal shape the player needs — satisfied by both summary and full DTOs. */
export interface StreamPlayable {
  readonly title: string;
  readonly status: string;
  readonly thumbnailUrl: string | null;
}

@Component({
  selector: 'app-stream-player',
  standalone: true,
  imports: [StreamStatusBadgeComponent],
  templateUrl: './stream-player.component.html',
  styleUrl: './stream-player.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamPlayerComponent implements OnDestroy {
  public readonly stream = input.required<StreamPlayable>();
  /** HLS play URL — when set and stream is LIVE, initializes hls.js playback. */
  public readonly playUrl = input<string | null>(null);

  private readonly destroyRef = inject(DestroyRef);

  protected readonly videoEl =
    viewChild<ElementRef<HTMLVideoElement>>('videoPlayer');

  protected readonly placeholder = 'img/stream-placeholder.svg';

  protected readonly isLive = computed(
    () => this.stream().status.toLowerCase() === 'live',
  );

  protected readonly poster = computed(
    () => this.stream().thumbnailUrl || this.placeholder,
  );

  private hls: Hls | null = null;
  private hlsStarted = false;

  /**
   * Starts HLS playback when both {@code playUrl} and the video element are
   * available. A field initializer so {@code effect()} runs within Angular's
   * injection context — {@code ngOnInit} is too late.
   */
  private hlsEffect = effect(() => {
    const url = this.playUrl();
    const el = this.videoEl();
    if (url && el && !this.hlsStarted) {
      this.hlsStarted = true;
      this.startHls(url, el.nativeElement);
    }
  });

  public ngOnDestroy(): void {
    this.destroyHls();
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
