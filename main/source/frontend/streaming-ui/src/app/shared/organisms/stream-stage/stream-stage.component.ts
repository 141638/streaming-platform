import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { StreamChatShellComponent } from '../../molecules/stream-chat-shell/stream-chat-shell.component';
import {
  StreamPlayable,
  StreamPlayerComponent,
} from '../stream-player/stream-player.component';

/**
 * The "stage": a player beside a chat shell, shown on both the dashboard and
 * the go-live screen. Row layout on large screens; stacks under `lg`.
 */
@Component({
  selector: 'app-stream-stage',
  standalone: true,
  imports: [StreamPlayerComponent, StreamChatShellComponent],
  templateUrl: './stream-stage.component.html',
  styleUrl: './stream-stage.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamStageComponent {
  public readonly stream = input.required<StreamPlayable>();
  public readonly playUrl = input<string | null>(null);

  protected readonly isLive = computed(
    () => this.stream().status.toLowerCase() === 'live',
  );
}
