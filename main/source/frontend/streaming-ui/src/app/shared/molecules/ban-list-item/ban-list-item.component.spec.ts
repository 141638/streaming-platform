import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { BanListItemComponent, DurationChange } from './ban-list-item.component';

describe('BanListItemComponent', () => {
  let fixture: ComponentFixture<BanListItemComponent>;
  let component: BanListItemComponent;

  const now = Date.parse('2026-07-11T00:00:00Z');
  const baseBan: BanResponseDto = {
    id: 'ban-1',
    roomId: 'room-1',
    bannedSubject: 'auth0|abcdef1234567890',
    bannedUsername: 'griefer',
    bannedBySubject: 'auth0|moderator99999',
    bannedByUsername: 'modmin',
    reason: 'Spamming links',
    createdAt: '2026-07-11T00:00:00Z',
    expiresAt: null,
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BanListItemComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(BanListItemComponent);
    component = fixture.componentInstance;
  });

  const render = (ban: BanResponseDto, nowMs = now): void => {
    fixture.componentRef.setInput('ban', ban);
    fixture.componentRef.setInput('nowMs', nowMs);
    fixture.detectChanges();
  };

  const editorButtons = () =>
    fixture.debugElement.queryAll(By.css('.ban-duration-editor button'));
  // [0] = shorten (chevron-down), [1] = extend (chevron-up)

  it('renders the banned username as the identity line', () => {
    render(baseBan);
    const identity = fixture.debugElement.query(By.css('.ban-identity'));
    expect(identity.nativeElement.textContent).toContain('griefer');
  });

  it('falls back to the truncated subject when the username is null', () => {
    render({ ...baseBan, bannedUsername: null });
    const identity = fixture.debugElement.query(By.css('.ban-identity'));
    expect(identity.nativeElement.textContent).toContain('auth0|ab');
  });

  it('renders the moderator name in the meta line', () => {
    render(baseBan);
    const meta = fixture.debugElement.query(By.css('.ban-meta'));
    expect(meta.nativeElement.textContent).toContain('by modmin');
  });

  it('shows the reason when present', () => {
    render(baseBan);
    const reason = fixture.debugElement.query(By.css('.ban-reason'));
    expect(reason.nativeElement.textContent).toContain('Spamming links');
  });

  it('falls back to "No reason given" when reason is null', () => {
    render({ ...baseBan, reason: null });
    const reason = fixture.debugElement.query(By.css('.ban-reason'));
    expect(reason.nativeElement.textContent).toContain('No reason given');
  });

  it('renders "Permanent" as raw styled text (no p-tag) when expiresAt is null', () => {
    render(baseBan);
    expect(fixture.debugElement.query(By.css('p-tag'))).toBeNull();
    const expires = fixture.debugElement.query(By.css('.ban-expires'));
    expect(expires.nativeElement.textContent).toContain('Permanent');
    expect(
      expires.nativeElement.classList.contains('ban-expires-permanent'),
    ).toBe(true);
  });

  it('renders an "expires in" countdown for a temporary ban', () => {
    render({ ...baseBan, expiresAt: '2026-07-11T02:00:00Z' }, now);
    const expires = fixture.debugElement.query(By.css('.ban-expires'));
    expect(expires.nativeElement.textContent).toContain('expires in 2h');
  });

  it('emits the banned subject when Unban is clicked', () => {
    render(baseBan);
    let emitted: string | undefined;
    component.unban.subscribe((subject) => (emitted = subject));

    fixture.debugElement.query(By.css('.ban-unban-btn')).nativeElement.click();

    expect(emitted).toBe(baseBan.bannedSubject);
  });

  it('steps the duration up one ladder rung (24h → 7d)', () => {
    render({ ...baseBan, expiresAt: '2026-07-12T00:00:00Z' }); // 24h span → rung 1
    let change: DurationChange | undefined;
    component.durationChange.subscribe((c) => (change = c));

    editorButtons()[1].nativeElement.click(); // extend

    expect(change).toEqual({
      subject: baseBan.bannedSubject,
      durationSeconds: 604800,
    });
  });

  it('steps the duration down one ladder rung (24h → 1h)', () => {
    render({ ...baseBan, expiresAt: '2026-07-12T00:00:00Z' }); // 24h span → rung 1
    let change: DurationChange | undefined;
    component.durationChange.subscribe((c) => (change = c));

    editorButtons()[0].nativeElement.click(); // shorten

    expect(change).toEqual({
      subject: baseBan.bannedSubject,
      durationSeconds: 3600,
    });
  });

  it('disables the extend button at the permanent (top) rung', () => {
    render(baseBan); // permanent
    expect(editorButtons()[1].nativeElement.disabled).toBe(true);
  });
});
