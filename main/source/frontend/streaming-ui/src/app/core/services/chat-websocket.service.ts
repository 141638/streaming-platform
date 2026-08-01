import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, Observable, Subject, throwError } from 'rxjs';
import { ChatMessageResponseDto, MessageType } from '../contracts/chat-message-response.dto';
import { AuthService } from './auth.service';

const MAX_BACKOFF_MS = 30_000;
const BASE_BACKOFF_MS = 1_000;
const MAX_RETRIES_BEFORE_FALLBACK = 5;
const SEND_TIMEOUT_MS = 30_000;

interface PendingSend {
  resolve: (dto: ChatMessageResponseDto) => void;
  reject: (err: Error) => void;
}

type ConnectionState = 'connected' | 'disconnected' | 'fallback';

@Injectable({ providedIn: 'root' })
export class ChatWebsocketService {
  private readonly authService = inject(AuthService);

  // ── Public observables ────────────────────────────────────────────────────

  private readonly messageSubject = new Subject<ChatMessageResponseDto>();
  public readonly message$: Observable<ChatMessageResponseDto> =
    this.messageSubject.asObservable();

  private readonly connectionState =
    new BehaviorSubject<ConnectionState>('disconnected');
  public readonly connectionState$: Observable<ConnectionState> =
    this.connectionState.asObservable();

  private readonly fallbackSubject = new Subject<void>();
  public readonly fallback$: Observable<void> =
    this.fallbackSubject.asObservable();

  // ── Internal state ────────────────────────────────────────────────────────

  private ws: WebSocket | null = null;
  private reconnectAttempts = 0;
  private currentRoomKey: string | null = null;
  private intentionalClose = false;
  private readonly pendingSends = new Map<string, PendingSend>();
  private clientIdCounter = 0;
  private reconnectTimerId: ReturnType<typeof setTimeout> | null = null;

  // ── Public API ────────────────────────────────────────────────────────────

  /**
   * Open a WebSocket connection to the given chat room.
   * Automatically disconnects any previous connection.
   */
  public connect(roomKey: string): void {
    this.disconnect();
    this.intentionalClose = false;
    this.reconnectAttempts = 0;
    this.currentRoomKey = roomKey;
    this.clearReconnectTimer();
    this.openWebSocket(roomKey);
  }

  /**
   * Gracefully close the current WebSocket connection.
   * No automatic reconnect will be attempted.
   */
  public disconnect(): void {
    this.intentionalClose = true;
    this.currentRoomKey = null;
    this.clearReconnectTimer();

    for (const [, pending] of this.pendingSends) {
      pending.reject(new Error('Connection closed'));
    }
    this.pendingSends.clear();

    const socket = this.ws;
    this.ws = null;
    if (socket !== null && socket.readyState !== WebSocket.CLOSED) {
      socket.close(1000);
    }

    this.connectionState.next('disconnected');
  }

  /**
   * Send a message to the current room and receive the correlated response.
   * The returned Observable emits once and completes, or errors on timeout /
   * disconnection.
   */
  public send(content: string): Observable<ChatMessageResponseDto> {
    if (this.ws === null || this.ws.readyState !== WebSocket.OPEN) {
      return throwError(
        () => new Error('WebSocket is not connected'),
      );
    }

    const clientId = String(this.clientIdCounter++);
    const frame = JSON.stringify({ type: 'send', clientId, content });

    try {
      this.ws.send(frame);
    } catch {
      return throwError(
        () => new Error('WebSocket send failed: connection is not open'),
      );
    }

    return new Observable<ChatMessageResponseDto>((observer) => {
      this.pendingSends.set(clientId, {
        resolve: (dto) => {
          observer.next(dto);
          observer.complete();
        },
        reject: (err) => observer.error(err),
      });

      const timeout = setTimeout(() => {
        this.pendingSends.delete(clientId);
        observer.error(new Error('Send timeout'));
      }, SEND_TIMEOUT_MS);

      return () => {
        clearTimeout(timeout);
        this.pendingSends.delete(clientId);
      };
    });
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private openWebSocket(roomKey: string): void {
    const token = this.authService.accessToken();

    if (token === null) {
      console.warn(
        '[ChatWebsocketService] No access token available — scheduling reconnect',
      );
      this.scheduleReconnect();
      return;
    }

    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${location.host}/api/chat/v1/rooms/${encodeURIComponent(roomKey)}/ws?access_token=${encodeURIComponent(token)}`;

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = (): void => {
      this.reconnectAttempts = 0;
      this.connectionState.next('connected');
    };

    ws.onmessage = (event: MessageEvent): void => {
      const raw = JSON.parse(event.data) as Record<string, unknown>;
      const msgType = raw['type'] as string | undefined;

      if (msgType === 'message') {
        const clientId = raw['clientId'] as string | null | undefined;
        const dto: ChatMessageResponseDto = {
          id: raw['id'] as string,
          roomKey: raw['roomKey'] as string,
          authorSubject: raw['authorSubject'] as string,
          authorUsername: (raw['authorUsername'] as string) ?? null,
          authorAvatarUrl: (raw['authorAvatarUrl'] as string) ?? null,
          body: raw['body'] as string,
          messageType: (raw['messageType'] as MessageType) ?? MessageType.NORMAL,
          giftAmount: (raw['giftAmount'] as number) ?? null,
          giftCurrency: (raw['giftCurrency'] as string) ?? null,
          createdAt: raw['createdAt'] as string,
          mentions: (raw['mentions'] as string[]) ?? [],
        };

        if (clientId !== null && clientId !== undefined) {
          const pending = this.pendingSends.get(clientId);
          if (pending !== undefined) {
            this.pendingSends.delete(clientId);
            pending.resolve(dto);
          }
        }

        this.messageSubject.next(dto);
      } else if (msgType === 'error') {
        const clientId = raw['clientId'] as string | null | undefined;
        const errMsg = String(raw['message'] ?? 'Unknown error');

        if (clientId !== null && clientId !== undefined) {
          const pending = this.pendingSends.get(clientId);
          if (pending !== undefined) {
            this.pendingSends.delete(clientId);
            pending.reject(new Error(errMsg));
          }
        } else {
          for (const [, pending] of this.pendingSends) {
            pending.reject(new Error(errMsg));
          }
          this.pendingSends.clear();
        }
      }
    };

    ws.onclose = (event: CloseEvent): void => {
      if (this.ws !== ws) {
        return; // stale socket — replaced by a newer connection
      }
      this.ws = null;

      if (!this.intentionalClose && event.code !== 1000) {
        this.scheduleReconnect();
      } else {
        this.connectionState.next('disconnected');
      }
    };

    ws.onerror = (): void => {
      // no-op — onclose always follows onerror and handles the state transition
    };
  }

  private scheduleReconnect(): void {
    this.reconnectAttempts++;

    if (this.reconnectAttempts > MAX_RETRIES_BEFORE_FALLBACK) {
      this.connectionState.next('fallback');
      this.fallbackSubject.next();
      return;
    }

    const delay =
      Math.min(
        BASE_BACKOFF_MS * Math.pow(2, this.reconnectAttempts - 1),
        MAX_BACKOFF_MS,
      ) +
      Math.random() * 1000;

    this.connectionState.next('disconnected');

    this.reconnectTimerId = setTimeout(() => {
      this.reconnectTimerId = null;
      if (this.currentRoomKey !== null) {
        this.openWebSocket(this.currentRoomKey);
      }
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimerId !== null) {
      clearTimeout(this.reconnectTimerId);
      this.reconnectTimerId = null;
    }
  }
}
