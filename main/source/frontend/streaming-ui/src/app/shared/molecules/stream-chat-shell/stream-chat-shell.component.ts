import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';

/**
 * Presentational chat shell — layout only. No service, no WebSocket, no polling.
 * The live chat wiring (see {@link ChatPanelComponent}) needs a room key that
 * streams do not yet expose; this shell reserves the space and UX until then.
 */
@Component({
  selector: 'app-stream-chat-shell',
  standalone: true,
  imports: [ButtonModule, InputTextModule],
  templateUrl: './stream-chat-shell.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StreamChatShellComponent {
  /** When true, the stream is live so chat is "coming" rather than "offline". */
  public readonly live = input<boolean>(false);

  protected readonly emptyMessage = computed(() =>
    this.live()
      ? 'Live chat is not connected yet.'
      : 'Chat will appear when the stream is live.',
  );
}
