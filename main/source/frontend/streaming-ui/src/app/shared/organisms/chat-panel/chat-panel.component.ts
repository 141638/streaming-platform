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
  computed,
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
import { DrawerModule } from 'primeng/drawer';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { catchError, finalize, interval, of, switchMap } from 'rxjs';
import {
  ChatMessageResponseDto,
  MessageType,
} from '../../../core/contracts/chat-message-response.dto';
import { RoomResponseDto } from '../../../core/contracts/room-response.dto';
import { dicebearAvatarUrl, truncateSub } from '../../../core/lib/avatar';
import { friendlyChatMessage, parseChatApiError } from '../../../core/lib/chat-error';
import { AuthService } from '../../../core/services/auth.service';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import { ChatService } from '../../../core/services/chat.service';
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
    DrawerModule,
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
})
export class ChatPanelComponent implements AfterViewInit, OnDestroy {
  /** The room's external key — determines which room to connect to. */
  @Input({ required: true }) public roomKey!: string;

  private readonly viewportRef = viewChild.required(CdkVirtualScrollViewport);

  private readonly chatService = inject(ChatService);
  private readonly authService = inject(AuthService);
  protected readonly mod = inject(ChatModerationService);
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
      authorUsername: this.currentUsername(),
      authorAvatarUrl: null,
      body: content,
      messageType: MessageType.NORMAL,
      giftAmount: null,
      giftCurrency: null,
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
          if (parseChatApiError(err)?.code === 'CHAT_USER_BANNED') {
            this.messages.update((msgs) =>
              msgs.filter((m) => m.clientId !== clientId),
            );
            this.bannedState.set(true);
            return;
          }
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
        error: (err: unknown) => this.errorMessage.set(this.moderationError(err)),
      });

    this.closeBanDialog();
  }

  /** Lift a subject's ban inline from a message row. */
  protected quickUnban(msg: DisplayMessage): void {
    this.mod
      .unban(this.roomKey, msg.authorSubject)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        error: (err: unknown) => this.errorMessage.set(this.moderationError(err)),
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
          this.applyModerationCapability(room);
          // Enforcement floor on room load: a banned viewer sees the disabled input
          // + banner immediately, without waiting for a failed send or the (future)
          // push pipeline. The 403 floor in send() stays as a backstop.
          this.bannedState.set(room.viewerBanned);
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
