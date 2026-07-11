import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BanResponseDto } from '../contracts/ban-response.dto';
import { ChatModerationService } from './chat-moderation.service';

describe('ChatModerationService', () => {
  let service: ChatModerationService;
  let httpTesting: HttpTestingController;

  const roomKey = 'room-1';
  const base = `/api/chat/v1/rooms/${roomKey}/bans`;

  const makeBan = (
    subject: string,
    expiresAt: string | null = null,
  ): BanResponseDto => ({
    id: `id-${subject}`,
    roomId: 'r1',
    bannedSubject: subject,
    bannedBySubject: 'mod-1',
    reason: null,
    createdAt: '2026-07-11T00:00:00Z',
    expiresAt,
  });

  const authzDenied = {
    body: { code: 'AUTHZ_DENIED', message: 'Access denied' },
    opts: { status: 403, statusText: 'Forbidden' },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        ChatModerationService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(ChatModerationService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('loadBans populates bans and bannedSubjects', () => {
    service.loadBans(roomKey).subscribe();

    const req = httpTesting.expectOne(base);
    expect(req.request.method).toBe('GET');
    req.flush([makeBan('userA')]);

    expect(service.bans().length).toBe(1);
    expect(service.bannedSubjects().has('userA')).toBe(true);
  });

  it('ban prepends the created ban on success', () => {
    service
      .ban(roomKey, { bannedSubject: 'userB', durationSeconds: 3600 })
      .subscribe();

    const req = httpTesting.expectOne(base);
    expect(req.request.method).toBe('POST');
    expect(req.request.body.bannedSubject).toBe('userB');
    req.flush(makeBan('userB'));

    expect(service.bannedSubjects().has('userB')).toBe(true);
  });

  it('ban leaves the roster unchanged on error', () => {
    service
      .ban(roomKey, { bannedSubject: 'userB' })
      .subscribe({ error: () => {} });

    httpTesting.expectOne(base).flush(authzDenied.body, authzDenied.opts);

    expect(service.bans().length).toBe(0);
  });

  it('unban optimistically removes then confirms on 204', () => {
    service.loadBans(roomKey).subscribe();
    httpTesting.expectOne(base).flush([makeBan('userC')]);
    expect(service.bannedSubjects().has('userC')).toBe(true);

    service.unban(roomKey, 'userC').subscribe();
    expect(service.bannedSubjects().has('userC')).toBe(false);

    httpTesting
      .expectOne(`${base}/userC`)
      .flush(null, { status: 204, statusText: 'No Content' });
    expect(service.bannedSubjects().has('userC')).toBe(false);
  });

  it('unban restores the ban on error', () => {
    service.loadBans(roomKey).subscribe();
    httpTesting.expectOne(base).flush([makeBan('userC')]);

    service.unban(roomKey, 'userC').subscribe({ error: () => {} });
    expect(service.bannedSubjects().has('userC')).toBe(false);

    httpTesting
      .expectOne(`${base}/userC`)
      .flush(authzDenied.body, authzDenied.opts);
    expect(service.bannedSubjects().has('userC')).toBe(true);
  });
});
