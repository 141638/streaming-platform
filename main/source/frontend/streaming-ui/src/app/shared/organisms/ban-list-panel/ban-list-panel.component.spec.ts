import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { ChatModerationService } from '../../../core/services/chat-moderation.service';
import { BanListPanelComponent } from './ban-list-panel.component';

describe('BanListPanelComponent', () => {
  let fixture: ComponentFixture<BanListPanelComponent>;
  let component: BanListPanelComponent;
  let httpTesting: HttpTestingController;

  const roomKey = 'room-1';
  const bansUrl = `/api/chat/v1/rooms/${roomKey}/bans`;

  const makeBan = (subject: string): BanResponseDto => ({
    id: `id-${subject}`,
    roomId: 'r1',
    bannedSubject: subject,
    bannedUsername: subject,
    bannedBySubject: 'mod-1',
    bannedByUsername: 'mod',
    reason: null,
    createdAt: '2026-07-11T00:00:00Z',
    expiresAt: null,
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BanListPanelComponent, NoopAnimationsModule],
      providers: [
        ChatModerationService,
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(BanListPanelComponent);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('roomKey', roomKey);
  });

  afterEach(() => httpTesting.verify());

  it('loads bans on init and renders one row per active ban', () => {
    fixture.detectChanges(); // ngOnInit → loadBans

    const req = httpTesting.expectOne(bansUrl);
    expect(req.request.method).toBe('GET');
    req.flush([makeBan('userA'), makeBan('userB')]);
    fixture.detectChanges();

    const rows = fixture.debugElement.queryAll(By.css('app-ban-list-item'));
    expect(rows.length).toBe(2);
  });

  it('shows the empty state when there are no bans', () => {
    fixture.detectChanges();
    httpTesting.expectOne(bansUrl).flush([]);
    fixture.detectChanges();

    const empty = fixture.debugElement.query(By.css('.ban-empty'));
    expect(empty).toBeTruthy();
    expect(empty.nativeElement.textContent).toContain('No one is banned.');
  });

  it('surfaces a friendly error when the load fails', () => {
    fixture.detectChanges();
    httpTesting
      .expectOne(bansUrl)
      .flush(
        { code: 'AUTHZ_DENIED', message: 'nope' },
        { status: 403, statusText: 'Forbidden' },
      );
    fixture.detectChanges();

    expect(component['error']()).toBe("You don't have permission to do that.");
    expect(fixture.debugElement.query(By.css('p-message'))).toBeTruthy();
  });

  it('re-requests the roster on refresh', () => {
    fixture.detectChanges();
    httpTesting.expectOne(bansUrl).flush([makeBan('userA')]);
    fixture.detectChanges();

    component['onRefresh']();

    const req = httpTesting.expectOne(bansUrl);
    expect(req.request.method).toBe('GET');
    req.flush([makeBan('userA'), makeBan('userB')]);
  });

  it('opens a confirm dialog on request (no DELETE yet), then unbans on confirm', () => {
    fixture.detectChanges();
    httpTesting.expectOne(bansUrl).flush([makeBan('userA')]);
    fixture.detectChanges();

    component['onUnbanRequest']('userA'); // opens confirm — no HTTP yet
    httpTesting.expectNone(`${bansUrl}/userA`);

    component['confirmUnban'](); // now performs the DELETE

    const req = httpTesting.expectOne(`${bansUrl}/userA`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('does not unban when the confirm dialog is cancelled', () => {
    fixture.detectChanges();
    httpTesting.expectOne(bansUrl).flush([makeBan('userA')]);
    fixture.detectChanges();

    component['onUnbanRequest']('userA');
    component['cancelUnban']();

    httpTesting.expectNone(`${bansUrl}/userA`);
  });

  it('applies an inline duration change via PATCH', () => {
    fixture.detectChanges();
    httpTesting.expectOne(bansUrl).flush([makeBan('userA')]);
    fixture.detectChanges();

    component['onDurationChange']({ subject: 'userA', durationSeconds: 3600 });

    const req = httpTesting.expectOne(`${bansUrl}/userA`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ durationSeconds: 3600 });
    req.flush({ ...makeBan('userA'), expiresAt: '2026-07-11T01:00:00Z' });
  });
});
