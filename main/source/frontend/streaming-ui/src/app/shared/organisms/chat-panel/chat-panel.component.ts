import {
  CdkFixedSizeVirtualScroll,
  CdkVirtualForOf,
  CdkVirtualScrollViewport,
} from '@angular/cdk/scrolling';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  DestroyRef,
  inject,
  Input,
  OnDestroy,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { catchError, finalize, interval, of, switchMap } from 'rxjs';
import { ChatMessageResponseDto } from '../../../core/contracts/chat-message-response.dto';
import { RoomResponseDto } from '../../../core/contracts/room-response.dto';
import { AuthService } from '../../../core/services/auth.service';
import { ChatService } from '../../../core/services/chat.service';

/** Client-side message status for optimistic sends. */
type MessageStatus = 'sending' | 'failed' | 'sent';

interface DisplayMessage extends ChatMessageResponseDto {
  readonly status: MessageStatus;
  readonly clientId: string;
}

const INITIAL_PAGE_SIZE = 50;
const LAZY_PAGE_SIZE = 30;
const POLL_INTERVAL_MS = 3000;
const NEAR_BOTTOM_THRESHOLD = 80;
const NEAR_TOP_THRESHOLD = 120;

@Component({
  selector: 'app-chat-panel',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    ButtonModule,
    DatePipe,
    InputTextModule,
    MessageModule,
    ProgressSpinnerModule,
    CdkVirtualScrollViewport,
    CdkVirtualForOf,
    CdkFixedSizeVirtualScroll,
  ],
  templateUrl: './chat-panel.component.html',
  styleUrl: './chat-panel.component.scss',
})
export class ChatPanelComponent implements AfterViewInit, OnDestroy {
  /** The room's external key — determines which room to connect to. */
  @Input({ required: true }) public roomKey!: string;

  private readonly viewportRef = viewChild.required(CdkVirtualScrollViewport);

  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  protected readonly messages = signal<DisplayMessage[]>([]);
  protected readonly loading = signal(true);
  protected readonly sending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly roomStatus = signal<'active' | 'archived' | 'not-found'>(
    'active',
  );
  protected readonly showScrollButton = signal(false);
  protected readonly showNewMessageHint = signal(false);
  protected readonly loadingOlder = signal(false);
  protected readonly hasMoreBefore = signal(true);

  protected readonly messageInput = this.fb.control('');

  protected readonly currentUserSub = signal<string | null>(null);
  private clientIdCounter = 0;
  private isNearBottom = true;
  private oldestCursor: string | null = null;

  // ── Lifecycle ──────────────────────────────────────────────────────────

  public ngAfterViewInit(): void {
    this.resolveCurrentUser();
    this.checkRoomStatus();
  }

  public ngOnDestroy(): void {
    // polling stops via takeUntilDestroyed — nothing manual needed
  }

  // ── Public API ─────────────────────────────────────────────────────────

  /** Scroll event — determines near-bottom/near-top for lazy load & FABs. */
  protected onScroll(): void {
    const vp = this.viewportRef();
    const el = vp.elementRef.nativeElement;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    this.isNearBottom = distFromBottom < NEAR_BOTTOM_THRESHOLD;
    this.showScrollButton.set(!this.isNearBottom);

    if (this.isNearBottom) {
      this.showNewMessageHint.set(false);
    }

    // lazy-load trigger: near the top
    if (
      el.scrollTop < NEAR_TOP_THRESHOLD &&
      this.hasMoreBefore() &&
      !this.loadingOlder()
    ) {
      this.loadOlderMessages();
    }
  }

  /** Send the current message. */
  protected send(): void {
    const content = this.messageInput.value?.trim();
    if (!content || this.sending()) {
      return;
    }

    this.errorMessage.set(null);
    this.sending.set(true);
    this.messageInput.setValue('');

    const clientId = this.generateClientId();
    const temp: DisplayMessage = {
      id: clientId,
      roomKey: this.roomKey,
      authorSubject: this.currentUserSub() ?? 'you',
      body: content,
      createdAt: new Date().toISOString(),
      status: 'sending',
      clientId,
    };

    this.messages.update((msgs) => [...msgs, temp]);
    this.scrollToBottom();

    this.chatService
      .sendMessage(this.roomKey, content)
      .pipe(
        finalize(() => this.sending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => this.replaceTempMessage(clientId, response),
        error: (err: unknown) => {
          this.markMessageFailed(clientId);
          const message =
            err instanceof HttpErrorResponse && err.status === 400
              ? 'Failed to send message. The room may be archived.'
              : 'Network error. Please try again.';
          this.errorMessage.set(message);
        },
      });
  }

  /** Retry a failed message. */
  protected retry(clientId: string): void {
    const failed = this.messages().find((m) => m.clientId === clientId);
    if (!failed || failed.status !== 'failed') {
      return;
    }

    this.messages.update((msgs) => msgs.filter((m) => m.clientId !== clientId));

    this.errorMessage.set(null);
    this.sending.set(true);

    this.chatService
      .sendMessage(this.roomKey, failed.body)
      .pipe(
        finalize(() => this.sending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => this.addServerMessage(response),
        error: () => {
          this.messages.update((msgs) => [
            ...msgs,
            { ...failed, status: 'failed' as const },
          ]);
          this.errorMessage.set('Network error. Please try again.');
        },
      });
  }

  /** Scroll to the bottom of the message list. */
  protected scrollToBottom(): void {
    requestAnimationFrame(() => {
      const vp = this.viewportRef();
      if (vp) {
        vp.scrollTo({ bottom: 0, behavior: 'auto' });
      }
      this.showScrollButton.set(false);
      this.showNewMessageHint.set(false);
    });
  }

  /** TrackBy for virtual scroll — unique per message. */
  protected trackByClientId(_index: number, msg: DisplayMessage): string {
    return msg.clientId;
  }

  // ── Private helpers: init ──────────────────────────────────────────────

  /**
   * Check the room status before starting polling and enabling input.
   */
  private checkRoomStatus(): void {
    this.chatService
      .getRoom(this.roomKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (room: RoomResponseDto) => {
          if (room.status === 'ARCHIVED') {
            this.roomStatus.set('archived');
            this.loadInitialMessages();
          } else {
            this.roomStatus.set('active');
            this.loadInitialMessages();
            this.startPolling();
          }
        },
        error: (err: unknown) => {
          if (err instanceof HttpErrorResponse && err.status === 404) {
            this.roomStatus.set('not-found');
            this.loading.set(false);
          } else {
            this.roomStatus.set('active');
            this.loadInitialMessages();
            this.startPolling();
          }
        },
      });
  }

  /**
   * Resolve the current user's subject from the stored JWT access token.
   */
  private resolveCurrentUser(): void {
    const token = this.authService.accessToken();
    if (!token) {
      return;
    }
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return;
      }
      const payload = JSON.parse(atob(parts[1]));
      const sub: string | undefined = payload?.sub;
      if (sub) {
        this.currentUserSub.set(sub);
      }
    } catch {
      // JWT payload is not critical — ignore parse failures
    }
  }

  // ── Private helpers: messages ──────────────────────────────────────────

  /**
   * Fetch the first page of recent messages and populate the chat.
   */
  private loadInitialMessages(): void {
    this.loading.set(true);
    this.chatService
      .getRecentMessages(this.roomKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          const reversed = [...data].reverse();
          this.messages.set(
            reversed.map((m) => ({
              ...m,
              status: 'sent' as const,
              clientId: m.id,
            })),
          );
          this.hasMoreBefore.set(data.length >= INITIAL_PAGE_SIZE);
          if (reversed.length > 0) {
            this.oldestCursor = reversed[0].createdAt;
          }
          this.loading.set(false);
          requestAnimationFrame(() => this.scrollToBottom());
        },
        error: () => {
          this.errorMessage.set('Failed to load messages.');
          this.loading.set(false);
        },
      });
  }

  /**
   * Load older messages when the user scrolls near the top.
   * Preserves scroll position after prepending items.
   */
  private loadOlderMessages(): void {
    if (!this.oldestCursor || this.loadingOlder()) {
      return;
    }

    this.loadingOlder.set(true);

    const vp = this.viewportRef();
    const el = vp.elementRef.nativeElement;
    const prevScrollHeight = el.scrollHeight;
    const prevScrollTop = el.scrollTop;

    this.chatService
      .getMessagesBefore(this.roomKey, this.oldestCursor, LAZY_PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          const older = [...data].reverse(); // chronological order
          if (older.length === 0) {
            this.hasMoreBefore.set(false);
            this.loadingOlder.set(false);
            return;
          }

          this.hasMoreBefore.set(data.length >= LAZY_PAGE_SIZE);
          this.oldestCursor = older[0].createdAt;

          const displayOlder: DisplayMessage[] = older.map((m) => ({
            ...m,
            status: 'sent' as const,
            clientId: m.id,
          }));

          this.messages.update((msgs) => [...displayOlder, ...msgs]);

          // preserve scroll position after prepending
          requestAnimationFrame(() => {
            requestAnimationFrame(() => {
              const newScrollHeight = el.scrollHeight;
              const heightAdded = newScrollHeight - prevScrollHeight;
              el.scrollTop = prevScrollTop + heightAdded;
              this.loadingOlder.set(false);
            });
          });
        },
        error: () => {
          this.loadingOlder.set(false);
        },
      });
  }

  // ── Private helpers: polling ───────────────────────────────────────────

  /**
   * Start the REST polling loop.
   */
  private startPolling(): void {
    interval(POLL_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.chatService
            .getRecentMessages(this.roomKey)
            .pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        if (!data || data.length === 0) {
          return;
        }
        const wasNearBottom = this.isNearBottom;
        const reversed = [...data].reverse();
        this.mergeServerMessages(reversed, wasNearBottom);
      });
  }

  /**
   * Merge server messages into the list. Auto-scrolls if the user
   * was near the bottom before the merge.
   */
  private mergeServerMessages(
    incoming: ChatMessageResponseDto[],
    wasNearBottom: boolean,
  ): void {
    const existingIds = new Set(this.messages().map((m) => m.id));
    const newMessages = incoming.filter((m) => !existingIds.has(m.id));
    if (newMessages.length === 0) {
      return;
    }

    const displayMessages: DisplayMessage[] = newMessages.map((m) => ({
      ...m,
      status: 'sent' as const,
      clientId: m.id,
    }));

    this.messages.update((msgs) => {
      const serverIds = new Set(displayMessages.map((m) => m.id));
      const filtered = msgs.filter(
        (m) => m.status === 'sending' || !serverIds.has(m.id),
      );
      return [...filtered, ...displayMessages].sort(
        (a, b) =>
          new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
      );
    });

    if (wasNearBottom) {
      requestAnimationFrame(() => this.scrollToBottom());
    } else {
      this.showNewMessageHint.set(true);
    }
  }

  // ── Private helpers: optimistic send ───────────────────────────────────

  private replaceTempMessage(
    clientId: string,
    response: ChatMessageResponseDto,
  ): void {
    this.messages.update((msgs) =>
      msgs.map((m) =>
        m.clientId === clientId
          ? { ...response, status: 'sent' as const, clientId: response.id }
          : m,
      ),
    );
  }

  private markMessageFailed(clientId: string): void {
    this.messages.update((msgs) =>
      msgs.map((m) =>
        m.clientId === clientId ? { ...m, status: 'failed' as const } : m,
      ),
    );
  }

  private addServerMessage(response: ChatMessageResponseDto): void {
    this.messages.update((msgs) => [
      ...msgs,
      { ...response, status: 'sent' as const, clientId: response.id },
    ]);
    requestAnimationFrame(() => this.scrollToBottom());
  }

  private generateClientId(): string {
    return `client-${Date.now()}-${++this.clientIdCounter}`;
  }
}
