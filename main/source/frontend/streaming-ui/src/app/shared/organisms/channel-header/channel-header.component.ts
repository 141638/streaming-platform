import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { AvatarModule } from 'primeng/avatar';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-channel-header',
  standalone: true,
  imports: [AvatarModule, ButtonModule],
  templateUrl: './channel-header.component.html',
  styleUrl: './channel-header.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelHeaderComponent {
  /** The channel owner's username (handle). */
  public readonly username = input.required<string>();
  /** Whether the channel owner is a verified streamer. */
  public readonly verified = input<boolean | null>(null);
  /** Whether the current viewer IS the channel owner. */
  public readonly isOwner = input(false);

  // ── Placeholder counts (shell — real data from channel-service later) ───

  protected readonly followerDisplay = '1.2K';
  protected readonly subscriberDisplay = '340';
  protected readonly videoDisplay = '12';

  // ── DiceBear avatar ─────────────────────────────────────────────────────

  protected readonly avatarUrl = computed(
    () =>
      `https://api.dicebear.com/9.x/thumbs/svg?seed=${encodeURIComponent(this.username())}`,
  );
}
