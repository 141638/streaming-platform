import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { ChatPanelComponent } from '../../shared/organisms/chat-panel/chat-panel.component';
import { ChatLoadTestComponent } from '../../shared/molecules/chat-load-test/chat-load-test.component';

@Component({
  selector: 'app-chat-room-page',
  standalone: true,
  imports: [ChatPanelComponent, ChatLoadTestComponent],
  templateUrl: './chat-room.page.html',
  styles: `
    :host {
      height: 100%;
      display: flex;
      flex-direction: column;
      flex: 1 1 auto;
      overflow-y: auto;
    }
  `,
})
export class ChatRoomPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly roomKey = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('roomKey') ?? '')),
  );
}
