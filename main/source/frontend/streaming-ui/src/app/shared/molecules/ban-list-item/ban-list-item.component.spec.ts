import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { BanResponseDto } from '../../../core/contracts/ban-response.dto';
import { BanListItemComponent } from './ban-list-item.component';

describe('BanListItemComponent', () => {
  let fixture: ComponentFixture<BanListItemComponent>;
  let component: BanListItemComponent;

  const now = Date.parse('2026-07-11T00:00:00Z');
  const baseBan: BanResponseDto = {
    id: 'ban-1',
    roomId: 'room-1',
    bannedSubject: 'auth0|abcdef1234567890',
    bannedBySubject: 'auth0|moderator99999',
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

  it('renders the truncated subject as the identity line', () => {
    render(baseBan);
    const identity = fixture.debugElement.query(By.css('.ban-identity'));
    expect(identity.nativeElement.textContent).toContain('auth0|ab');
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

  it('renders a Permanent tag when expiresAt is null', () => {
    render(baseBan);
    const tag = fixture.debugElement.query(By.css('p-tag'));
    expect(tag.nativeElement.textContent).toContain('Permanent');
  });

  it('renders an "expires in" tag for a temporary ban', () => {
    render({ ...baseBan, expiresAt: '2026-07-11T02:00:00Z' }, now);
    const tag = fixture.debugElement.query(By.css('p-tag'));
    expect(tag.nativeElement.textContent).toContain('expires in 2h');
  });

  it('emits the banned subject when Unban is clicked', () => {
    render(baseBan);
    let emitted: string | undefined;
    component.unban.subscribe((subject) => (emitted = subject));

    const button = fixture.debugElement.query(By.css('p-button button'));
    button.nativeElement.click();

    expect(emitted).toBe(baseBan.bannedSubject);
  });
});
