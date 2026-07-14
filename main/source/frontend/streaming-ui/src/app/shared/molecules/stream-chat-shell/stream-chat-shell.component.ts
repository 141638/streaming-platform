import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { ChatPanelComponent } from '../../organisms/chat-panel/chat-panel.component';

/**
 * Presentational chat wrapper. When a {@code roomKey} is provided, delegates
 * to the full {@link ChatPanelComponent}. Otherwise shows a placeholder.
 */
@Component({
  selector: 'app-stream-chat-shell',
  standalone: true,
  imports: [ButtonModule, InputTextModule, ChatPanelComponent],
  templateUrl: './stream-chat-shell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamChatShellComponent {
  /** When true, the stream is live. */
  public readonly live = input<boolean>(false);
  /** Chat room key — when set, the real chat panel renders. */
  public readonly roomKey = input<string | null>(null);

  protected readonly isConnected = computed(() => !!this.roomKey());

  protected readonly emptyMessage = computed(() =>
    this.live()
      ? 'Live chat is not connected yet.'
      : 'Chat will appear when the stream is live.',
  );
}
