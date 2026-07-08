import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
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
export class StreamPlayerComponent {
  public readonly stream = input.required<StreamPlayable>();
  /** HLS play URL — wired for the Phase-4 real player; unused by this shell. */
  public readonly playUrl = input<string | null>(null);

  protected readonly placeholder = 'img/stream-placeholder.svg';

  protected readonly isLive = computed(
    () => this.stream().status.toLowerCase() === 'live',
  );

  protected readonly poster = computed(
    () => this.stream().thumbnailUrl || this.placeholder,
  );
}
