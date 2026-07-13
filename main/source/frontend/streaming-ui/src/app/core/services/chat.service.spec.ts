import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ChatMessageResponseDto, MessageType } from '../contracts/chat-message-response.dto';
import { RoomResponseDto } from '../contracts/room-response.dto';
import { ChatService } from './chat.service';

describe('ChatService', () => {
  let service: ChatService;
  let httpTesting: HttpTestingController;

  const roomKey = 'test-room-123';
  const base = `/api/chat/v1/rooms`;

  const makeMessage = (overrides: Partial<ChatMessageResponseDto> = {}): ChatMessageResponseDto => ({
    id: 'msg-1',
    roomKey,
    authorSubject: 'user-sub',
    authorUsername: 'Alice',
    authorAvatarUrl: null,
    body: 'hello',
    messageType: MessageType.NORMAL,
    giftAmount: null,
    giftCurrency: null,
    createdAt: '2026-07-13T00:00:00Z',
    mentions: [],
    ...overrides,
  });

  const makeRoom = (overrides: Partial<RoomResponseDto> = {}): RoomResponseDto => ({
    externalKey: roomKey,
    status: 'ACTIVE',
    viewerCanModerate: false,
    viewerBanned: false,
    createdAt: '2026-07-13T00:00:00Z',
    archivedAt: null,
    ...overrides,
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ChatService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  // ── getRoom ─────────────────────────────────────────────────────────────

  it('should GET room metadata', () => {
    service.getRoom(roomKey).subscribe((room) => {
      expect(room.externalKey).toBe(roomKey);
      expect(room.status).toBe('ACTIVE');
    });

    const req = httpTesting.expectOne(`${base}/${roomKey}`);
    expect(req.request.method).toBe('GET');
    req.flush(makeRoom());
  });

  // ── sendMessage ─────────────────────────────────────────────────────────

  it('should POST a message with correct body', () => {
    service.sendMessage(roomKey, 'hello world').subscribe((msg) => {
      expect(msg.body).toBe('hello world');
      expect(msg.roomKey).toBe(roomKey);
    });

    const req = httpTesting.expectOne(`${base}/${roomKey}/messages`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ content: 'hello world' });
    req.flush(makeMessage({ body: 'hello world' }));
  });

  // ── getRecentMessages ───────────────────────────────────────────────────

  it('should GET recent messages', () => {
    service.getRecentMessages(roomKey).subscribe((msgs) => {
      expect(msgs.length).toBe(2);
    });

    const req = httpTesting.expectOne(`${base}/${roomKey}/messages/recent`);
    expect(req.request.method).toBe('GET');
    req.flush([makeMessage({ id: 'msg-1' }), makeMessage({ id: 'msg-2' })]);
  });

  // ── getMessagesBefore ───────────────────────────────────────────────────

  it('should GET messages before a cursor with limit param', () => {
    const cursor = '2026-07-13T00:00:00Z';
    service.getMessagesBefore(roomKey, cursor, 20).subscribe((msgs) => {
      expect(msgs.length).toBe(1);
    });

    const req = httpTesting.expectOne(
      `${base}/${roomKey}/messages/recent?before=${encodeURIComponent(cursor)}&limit=20`,
    );
    expect(req.request.method).toBe('GET');
    req.flush([makeMessage()]);
  });

  it('should default limit to 30 in getMessagesBefore when not specified', () => {
    service.getMessagesBefore(roomKey, 'cursor-1').subscribe();

    const req = httpTesting.expectOne(
      `${base}/${roomKey}/messages/recent?before=cursor-1&limit=30`,
    );
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  // ── Error handling ──────────────────────────────────────────────────────

  it('should propagate HTTP errors', () => {
    service.sendMessage(roomKey, 'test').subscribe({
      error: (err) => {
        expect(err.status).toBe(403);
      },
    });

    const req = httpTesting.expectOne(`${base}/${roomKey}/messages`);
    req.flush({ code: 'CHAT_USER_BANNED', message: 'Banned' }, {
      status: 403,
      statusText: 'Forbidden',
    });
  });
});
