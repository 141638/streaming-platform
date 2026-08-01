import {
  CdkFixedSizeVirtualScroll,
  CdkVirtualForOf,
  CdkVirtualScrollViewport,
} from '@angular/cdk/scrolling';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  Input,
  OnDestroy,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import {
  AutoCompleteCompleteEvent,
  AutoCompleteModule,
  AutoCompleteSelectEvent,
} from 'primeng/autocomplete';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { DrawerModule } from 'primeng/drawer';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { PopoverModule } from 'primeng/popover';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { catchError, finalize, interval, of, switchMap } from 'rxjs';
import {
  ChatMessageResponseDto,
  MessageType,
} from '../../../core/contracts/chat-message-response.dto';
import { RoomResponseDto } from '../../../core/contracts/room-response.dto';
import { dicebearAvatarUrl, truncateSub } from '../../../core/lib/avatar';
import { IdempotencyService } from '../../../core/services/idempotency.service';
import {
  friendlyChatMessage,
  parseChatApiError,
} from '../../../core/lib/chat-error';
import { AuthService } from '../../../core/services/auth.service';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import { ChatService } from '../../../core/services/chat.service';
import { ChatWebsocketService } from '../../../core/services/chat-websocket.service';
import { BanUserDialogComponent } from '../../molecules/ban-user-dialog/ban-user-dialog.component';
import { MessageModActionsComponent } from '../../molecules/message-mod-actions/message-mod-actions.component';
import { BanListPanelComponent } from '../ban-list-panel/ban-list-panel.component';

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

/** Curated emoji set for the in-chat picker — no dependency needed. */
const EMOJI_LIST: string[] = [
  '😀',
  '😂',
  '🤣',
  '😍',
  '🥰',
  '😎',
  '🤩',
  '😇',
  '🤔',
  '😅',
  '👍',
  '👎',
  '👏',
  '🙌',
  '💪',
  '🤝',
  '🔥',
  '🎉',
  '❤️',
  '💔',
  '😢',
  '😡',
  '🤬',
  '😱',
  '🥺',
  '🙏',
  '✨',
  '💯',
  '🎯',
  '⭐',
  '🍕',
  '☕',
  '🎮',
  '📺',
  '🎵',
  '📷',
  '💻',
  '🐱',
  '🐶',
  '🌻',
  '👋',
  '🤷',
  '💀',
  '👀',
  '🧠',
  '🗿',
  '🚀',
  '💩',
  '🫡',
  '🥳',
];

@Component({
  selector: 'app-chat-panel',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    AutoCompleteModule,
    ButtonModule,
    DatePipe,
    InputTextModule,
    MessageModule,
    PopoverModule,
    ProgressSpinnerModule,
    DrawerModule,
    DialogModule,
    CdkVirtualScrollViewport,
    CdkVirtualForOf,
    CdkFixedSizeVirtualScroll,
    BanListPanelComponent,
    BanUserDialogComponent,
    MessageModActionsComponent,
  ],
  providers: [ChatModerationService],
  templateUrl: './chat-panel.component.html',
  styleUrl: './chat-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChatPanelComponent implements AfterViewInit, OnDestroy {
  /** The room's external key — determines which room to connect to. */
  @Input({ required: true }) public roomKey!: string;

  private readonly viewportRef = viewChild.required(CdkVirtualScrollViewport);

  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  private readonly idempotencyService = inject(IdempotencyService);
  protected readonly mod = inject(ChatModerationService);
  private readonly wsService = inject(ChatWebsocketService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  protected readonly messageType = MessageType;
  protected readonly messages = signal<DisplayMessage[]>([]);
  protected readonly loading = signal(true);
  protected readonly sending = signal(false);
  protected readonly errorMessage = signal<string | null>(null);
  protected readonly bannedState = signal(false);
  protected readonly bannedMessage = computed(() =>
    friendlyChatMessage('CHAT_USER_BANNED'),
  );
  protected readonly canModerate = signal(false);
  protected readonly moderationOpen = signal(false);
  protected readonly banDialogOpen = signal(false);
  protected readonly banTarget = signal<{
    subject: string;
    username: string | null;
  } | null>(null);
  protected readonly pendingUnbanTarget = signal<{
    subject: string;
    username: string | null;
  } | null>(null);
  protected readonly pendingUnbanName = computed(() => {
    const target = this.pendingUnbanTarget();
    return target === null
      ? ''
      : (target.username ?? truncateSub(target.subject));
  });
  protected readonly banCountLabel = computed(() =>
    String(this.mod.bannedSubjects().size),
  );
  protected readonly roomStatus = signal<'active' | 'archived' | 'not-found'>(
    'active',
  );
  protected readonly showScrollButton = signal(false);
  protected readonly showNewMessageHint = signal(false);
  protected readonly loadingOlder = signal(false);
  protected readonly hasMoreBefore = signal(true);
  protected readonly inputPlaceholder = computed(() => {
    if (this.bannedState()) {
      return 'You are banned from this room';
    }
    return this.roomStatus() === 'archived'
      ? 'This room is archived'
      : 'Type a message…';
  });

  protected readonly messageInput = this.fb.control('');

  protected readonly currentUserSub = signal<string | null>(null);
  protected readonly currentUsername = signal<string | null>(null);
  /** The JWT subject of the streamer who owns this room. */
  protected readonly broadcasterSubject = signal<string | null>(null);

  /** Whether the given message author is the room's broadcaster. */
  protected isBroadcasterMessage(msg: DisplayMessage): boolean {
    const b = this.broadcasterSubject();
    return b !== null && msg.authorSubject === b;
  }

  // ── Emoji picker ────────────────────────────────────────────────────────

  protected readonly EMOJI_LIST = EMOJI_LIST;
  protected readonly emojiPickerOpen = signal(false);

  /** Saved cursor position — captured on mousedown before focus shifts away from the input. */
  private savedSelectionStart: number | null = null;
  private savedSelectionEnd: number | null = null;

  // ── @mention autocomplete ───────────────────────────────────────────────

  /** Unique chatters from the current message list, most recent first. */
  protected readonly uniqueChatters = computed(() => {
    const seen = new Set<string>();
    const chatters: string[] = [];
    for (let i = this.messages().length - 1; i >= 0; i--) {
      const name = this.messages()[i].authorUsername;
      if (name !== null && name !== 'System' && !seen.has(name)) {
        seen.add(name);
        chatters.push(name);
      }
    }
    return chatters;
  });

  /** Suggestions for p-autocomplete — set by completeMethod, consumed by template. */
  protected readonly mentionSuggestions = signal<string[]>([]);

  /** Whether the mention autocomplete overlay panel is currently visible. */
  protected readonly mentionPanelVisible = signal(false);

  /**
   * Snapshot of the input text and @ position captured in
   * {@link #completeMentions}, before p-autocomplete overwrites the input
   * on select. Used in {@link #onMentionSelect} to reconstruct the text
   * around the replaced mention.
   */
  private savedMentionStart: number | null = null;
  private savedOriginalQuery: string | null = null;

  /**
   * Guard flag set true in {@link #onMentionSelect} so the Enter keydown
   * handler (which fires after p-autocomplete has already selected the item
   * and cleared suggestions) can skip sending. Auto-clears via microtask.
   */
  private mentionJustSelected = false;

  /** Monotonic counter for API requests — only the latest response is applied. */
  private mentionRequestId = 0;

  private clientIdCounter = 0;
  private isNearBottom = true;
  private oldestCursor: string | null = null;
  /** Idempotency keys per clientId for safe retry after token refresh. */
  private readonly idempotencyKeyByClientId = new Map<string, string>();

  // ── Lifecycle ──────────────────────────────────────────────────────────

  public ngAfterViewInit(): void {
    this.resolveCurrentUser();
    this.checkRoomStatus();
  }

  public ngOnDestroy(): void {
    this.wsService.disconnect();
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
      authorUsername: this.currentUsername(),
      authorAvatarUrl: null,
      body: content,
      messageType: MessageType.NORMAL,
      giftAmount: null,
      giftCurrency: null,
      createdAt: new Date().toISOString(),
      mentions: [],
      status: 'sending',
      clientId,
    };

    this.messages.update((msgs) => [...msgs, temp]);
    this.scrollToBottom();

    const idempotencyKey = this.idempotencyService.newKey();
    this.idempotencyKeyByClientId.set(clientId, idempotencyKey);

    this.wsService
      .send(content)
      .pipe(
        finalize(() => this.sending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => {
          this.idempotencyKeyByClientId.delete(clientId);
          this.replaceTempMessage(clientId, response);
        },
        error: (err: unknown) => {
          if ((err as {code?:string})?.code === "CHAT_USER_BANNED") {
            this.messages.update((msgs) =>
              msgs.filter((m) => m.clientId !== clientId),
            );
            this.bannedState.set(true);
            return;
          }
          this.markMessageFailed(clientId);
          this.errorMessage.set('Network error. Please try again.');
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

    // Reuse the original idempotency key so the gateway returns the cached
    // response if the first attempt actually succeeded.
    const idempotencyKey =
      this.idempotencyKeyByClientId.get(clientId) ??
      this.idempotencyService.newKey();

    this.chatService
      .sendMessage(this.roomKey, failed.body, idempotencyKey)
      .pipe(
        finalize(() => this.sending.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (response) => {
          this.idempotencyKeyByClientId.delete(clientId);
          this.addServerMessage(response);
        },
        error: (err: unknown) => {
          if (parseChatApiError(err)?.code === 'CHAT_USER_BANNED') {
            this.bannedState.set(true);
            return;
          }
          this.messages.update((msgs) => [
            ...msgs,
            { ...failed, status: 'failed' as const },
          ]);
          this.errorMessage.set('Network error. Please try again.');
        },
      });
  }

  /** Clear the banned state so the user can retry (e.g. after a temp-ban lapses). */
  protected clearBanned(): void {
    this.bannedState.set(false);
    this.errorMessage.set(null);
  }

  // ── Moderation ─────────────────────────────────────────────────────────

  /** Toggle the moderation drawer. */
  protected toggleModeration(): void {
    this.moderationOpen.update((open) => !open);
  }

  /** Open the ban dialog targeting a specific message's author. */
  protected openBanDialog(msg: DisplayMessage): void {
    this.banTarget.set({
      subject: msg.authorSubject,
      username: msg.authorUsername,
    });
    this.banDialogOpen.set(true);
  }

  /** Close the ban dialog without issuing a ban. */
  protected closeBanDialog(): void {
    this.banDialogOpen.set(false);
  }

  /** Issue the ban for the current dialog target, then close the dialog. */
  protected confirmBan(payload: {
    reason: string | null;
    durationSeconds: number | null;
  }): void {
    const target = this.banTarget();
    if (!target) {
      this.closeBanDialog();
      return;
    }

    this.mod
      .ban(this.roomKey, {
        bannedSubject: target.subject,
        bannedUsername: target.username,
        reason: payload.reason,
        durationSeconds: payload.durationSeconds,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) =>
          this.errorMessage.set(this.moderationError(err)),
      });

    this.closeBanDialog();
  }

  /** Open the unban-confirm dialog for a message row's author. */
  protected requestUnban(msg: DisplayMessage): void {
    this.pendingUnbanTarget.set({
      subject: msg.authorSubject,
      username: msg.authorUsername,
    });
  }

  /** Dismiss the unban-confirm dialog without lifting the ban. */
  protected cancelUnban(): void {
    this.pendingUnbanTarget.set(null);
  }

  /**
   * The confirm dialog binds {@code visible} one-way off an object signal;
   * reconcile our state whenever PrimeNG drives {@code visibleChange} to false
   * (X / mask / ESC) so the close can't be reasserted mid-animation.
   */
  protected onUnbanDialogVisibleChange(visible: boolean): void {
    if (!visible) {
      this.cancelUnban();
    }
  }

  /** Lift the pending target's ban, then close the dialog. */
  protected confirmUnban(): void {
    const target = this.pendingUnbanTarget();
    this.pendingUnbanTarget.set(null);
    if (target === null) {
      return;
    }
    this.mod
      .unban(this.roomKey, target.subject)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) =>
          this.errorMessage.set(this.moderationError(err)),
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

  /**
   * Get the avatar URL for a message's author.
   * Uses authorUsername (deterministic DiceBear) with authorSubject fallback.
   */
  protected avatarUrlFor(msg: DisplayMessage): string {
    const seed = msg.authorUsername ?? msg.authorSubject;
    return dicebearAvatarUrl(seed);
  }

  /**
   * Get the display name for a message's author.
   * Prefers authorUsername; falls back to truncated authorSubject.
   */
  protected displayNameFor(msg: DisplayMessage): string {
    return msg.authorUsername ?? truncateSub(msg.authorSubject);
  }

  /**
   * Format the hover tooltip for a message timestamp in UTC.
   */
  protected timestampTooltip(createdAt: string): string {
    const d = new Date(createdAt);
    return d.toISOString().replace('T', ' ').substring(0, 19) + ' UTC';
  }

  // ── Private helpers: init ──────────────────────────────────────────────

  /**
   * Adopt the room's moderation capability. When the caller can moderate,
   * eagerly load the ban roster once so inline per-message state
   * ({@code bannedSubjects}) is populated without opening the drawer.
   */
  private applyModerationCapability(room: RoomResponseDto): void {
    this.canModerate.set(room.viewerCanModerate);
    if (!room.viewerCanModerate) {
      return;
    }
    this.mod
      .loadBans(this.roomKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: () => {
          // Non-fatal: inline badges simply stay unpopulated until refresh.
        },
      });
  }

  /** Map a moderation action failure to friendly, user-facing copy. */
  private moderationError(err: unknown): string {
    const parsed = parseChatApiError(err);
    return parsed === null
      ? 'Moderation action failed. Please try again.'
      : friendlyChatMessage(parsed.code);
  }

  /**
   * Check the room status before starting polling and enabling input.
   */
  private checkRoomStatus(): void {
    this.chatService
      .getRoom(this.roomKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (room: RoomResponseDto) => {
          this.broadcasterSubject.set(room.broadcasterSubject ?? null);
          this.applyModerationCapability(room);
          this.bannedState.set(room.viewerBanned);
          if (room.status === 'ARCHIVED') {
            this.roomStatus.set('archived');
            this.loadInitialMessages();
          } else {
            this.roomStatus.set('active');
            this.loadInitialMessages();
            this.startWebSocket();
          }
        },
        error: (err: unknown) => {
          if (err instanceof HttpErrorResponse && err.status === 404) {
            this.roomStatus.set('not-found');
            this.loading.set(false);
          } else {
            this.roomStatus.set('active');
            this.loadInitialMessages();
            this.startWebSocket();
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
      const attr = payload?.attr;
      const username: string | undefined = attr?.username;
      if (username) {
        this.currentUsername.set(username);
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

  private startWebSocket(): void {
    this.wsService.connect(this.roomKey);
    this.wsService.message$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((msg: ChatMessageResponseDto) => {
        const wasNearBottom = this.isNearBottom;
        this.mergeServerMessages([msg], wasNearBottom);
      });
    this.wsService.fallback$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        console.warn("[ChatPanel] WebSocket failed, switching to REST polling");
        this.startPolling();
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

  // ── Emoji picker ────────────────────────────────────────────────────────

  /** Save cursor position before focus is lost when clicking the emoji button. */
  protected captureCursor(): void {
    const input = document.getElementById(
      'chat-message-input',
    ) as HTMLInputElement | null;
    if (input) {
      this.savedSelectionStart = input.selectionStart;
      this.savedSelectionEnd = input.selectionEnd;
    }
  }

  /** Toggle the emoji picker overlay. Closes mentions if open. */
  protected toggleEmojiPicker(): void {
    this.emojiPickerOpen.update((v) => !v);
  }

  /** Insert an emoji at the saved cursor position and close the picker. */
  protected insertEmoji(emoji: string): void {
    this.insertAtCursor(emoji);
    this.emojiPickerOpen.set(false);
  }

  /** Insert text at the saved cursor position, restoring focus afterward. */
  private insertAtCursor(text: string): void {
    const input = document.getElementById(
      'chat-message-input',
    ) as HTMLInputElement | null;
    if (!input) return;
    // Use saved cursor position (captured on mousedown before blur), falling
    // back to the end of the current value if nothing was saved.
    const start =
      this.savedSelectionStart ?? input.selectionStart ?? input.value.length;
    const end = this.savedSelectionEnd ?? input.selectionEnd ?? start;
    const current = this.messageInput.value ?? '';
    this.messageInput.setValue(
      current.slice(0, start) + text + current.slice(end),
    );
    // Clear saved positions after use
    this.savedSelectionStart = null;
    this.savedSelectionEnd = null;
    requestAnimationFrame(() => {
      const pos = start + text.length;
      input.setSelectionRange(pos, pos);
      input.focus();
    });
  }

  // ── @mention autocomplete (p-autocomplete) ──────────────────────────────

  /**
   * Autocomplete complete-method — parses the input for a @mention trigger,
   * snapshots the original text (so {@link #onMentionSelect} can reconstruct
   * after p-autocomplete overwrites the input), then fetches suggestions
   * from local chatters and the participants API.
   */
  protected completeMentions(event: AutoCompleteCompleteEvent): void {
    const mention = this.detectMention(event.query, event.query.length);
    if (mention !== null) {
      // Snapshot original text before p-autocomplete can overwrite it on select
      this.savedMentionStart = mention.start;
      this.savedOriginalQuery = event.query;
      this.fetchMentionSuggestions(mention.query);
    } else {
      this.savedMentionStart = null;
      this.savedOriginalQuery = null;
      this.mentionSuggestions.set([]);
      this.mentionPanelVisible.set(false);
    }
  }

  /**
   * Autocomplete on-select handler — uses the saved original text (from
   * {@link #completeMentions}) to replace only the @query portion with the
   * selected username, leaving surrounding text intact. p-autocomplete has
   * already overwritten the form control value at this point, so we
   * reconstruct from the snapshot.
   *
   * <p>Sets {@link #mentionJustSelected} so the Enter keydown handler (which
   * fires after this, since p-autocomplete's internal handler runs on the
   * input before the event bubbles to our host binding) can skip sending.
   */
  protected onMentionSelect(event: AutoCompleteSelectEvent): void {
    const selected = event.value as string;
    const start = this.savedMentionStart;
    const original = this.savedOriginalQuery;
    if (start === null || original === null) return;

    const before = original.slice(0, start);
    // Compute the query length from the original text: @ + query → end of word or string
    const afterAt = original.slice(start + 1);
    const spaceOrEnd = afterAt.search(/[\s]|$/);
    const queryLen = spaceOrEnd === -1 ? afterAt.length : spaceOrEnd;
    const after = original.slice(start + 1 + queryLen);

    this.messageInput.setValue(before + '@' + selected + ' ' + after);
    this.mentionSuggestions.set([]);
    this.mentionPanelVisible.set(false);
    this.savedMentionStart = null;
    this.savedOriginalQuery = null;

    // Guard against the Enter keydown that's about to bubble to our handler
    this.mentionJustSelected = true;
    // Auto-clear after this event cycle so click-selects don't block the next Enter
    setTimeout(() => {
      this.mentionJustSelected = false;
    }, 0);

    const input = document.getElementById(
      'chat-message-input',
    ) as HTMLInputElement | null;
    const newPos = start + selected.length + 2; // @name + trailing space
    requestAnimationFrame(() => {
      input?.setSelectionRange(newPos, newPos);
      input?.focus();
    });
  }

  /** Find a valid @mention trigger in the text before cursor. */
  private detectMention(
    text: string,
    cursorPos: number,
  ): { query: string; start: number } | null {
    const before = text.slice(0, cursorPos);
    const atIndex = before.lastIndexOf('@');
    if (atIndex === -1) return null;
    const charBeforeAt = atIndex > 0 ? before[atIndex - 1] : ' ';
    if (!/[\s]/.test(charBeforeAt)) return null;
    const query = before.slice(atIndex + 1);
    if (query.includes(' ') || query.length > 32) return null;
    return { query, start: atIndex };
  }

  /**
   * Fetch mention suggestions: instant local results from visible chatters
   * first, then enriched with API results (anyone who ever chatted in this
   * room). Uses a monotonic request-id so only the latest API response is
   * applied, preventing stale merges on rapid typing.
   */
  private fetchMentionSuggestions(query: string): void {
    const q = query.toLowerCase();
    const chatters = this.uniqueChatters();
    // Tier 1: local chatters (instant)
    const local: string[] =
      q.length === 0
        ? chatters.slice(0, 8)
        : chatters.filter((n) => n.toLowerCase().startsWith(q)).slice(0, 8);
    // Exact-match fallback for unknown users
    if (local.length === 0 && q.length >= 2) {
      local.push(query);
    }
    this.mentionSuggestions.set(local);
    this.mentionPanelVisible.set(local.length > 0);

    // Tier 2: API participants (anyone who ever chatted in this room)
    const requestId = ++this.mentionRequestId;
    this.chatService
      .getParticipants(this.roomKey, q, 10)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (participants) => {
          // Stale response — a newer completeMethod call already fired
          if (requestId !== this.mentionRequestId) return;
          const seen = new Set(this.mentionSuggestions());
          for (const p of participants) {
            if (!seen.has(p)) seen.add(p);
          }
          this.mentionSuggestions.set([...seen].slice(0, 10));
        },
        error: () => {
          // Non-fatal: local results are already shown
        },
      });
  }

  /** p-autocomplete overlay panel became visible. */
  protected onMentionPanelShow(): void {
    // Panel visibility is also tracked via mentionPanelVisible,
    // but onShow confirms p-autocomplete actually rendered the overlay.
    this.mentionPanelVisible.set(true);
  }

  /** p-autocomplete overlay panel was dismissed (Escape, click-away, blur). */
  protected onMentionPanelHide(): void {
    this.mentionPanelVisible.set(false);
    this.savedMentionStart = null;
    this.savedOriginalQuery = null;
  }

  /**
   * Send on Enter when no mention dropdown is active.
   *
   * <p>p-autocomplete's internal keydown handler fires on the input element
   * before the event bubbles to our host binding. When the mention panel is
   * open pressing Enter selects the highlighted item and fires
   * {@link #onMentionSelect} first — which sets {@link #mentionJustSelected}.
   * We check that flag first so we don't send immediately after a selection.
   * {@link #mentionPanelVisible} is the fallback check for cases where
   * p-autocomplete shows the panel but no selection occurs.
   */
  protected onInputKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.shiftKey) return;

    // p-autocomplete just selected a mention → don't send
    if (this.mentionJustSelected) {
      this.mentionJustSelected = false;
      event.preventDefault();
      return;
    }

    // Mention panel is still open (arrow keys, etc.) → let p-autocomplete handle it
    if (this.mentionPanelVisible()) return;

    event.preventDefault();
    this.send();
  }

  /**
   * Whether a mention suggestion is a fallback — not a known chatter in the
   * current room. Rendered with a distinct "Add user" affordance in the template.
   */
  protected isMentionFallback(name: string): boolean {
    return !this.uniqueChatters().includes(name);
  }

  private generateClientId(): string {
    return `client-${Date.now()}-${++this.clientIdCounter}`;
  }
}
