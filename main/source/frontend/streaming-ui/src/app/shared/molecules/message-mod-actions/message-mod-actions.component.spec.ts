import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MessageModActionsComponent } from './message-mod-actions.component';

describe('MessageModActionsComponent', () => {
  let fixture: ComponentFixture<MessageModActionsComponent>;
  let component: MessageModActionsComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MessageModActionsComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(MessageModActionsComponent);
    component = fixture.componentInstance;
  });

  const render = (isBanned: boolean): void => {
    fixture.componentRef.setInput('authorSubject', 'auth0|abcdef1234567890');
    fixture.componentRef.setInput('authorUsername', 'griefer');
    fixture.componentRef.setInput('isBanned', isBanned);
    fixture.detectChanges();
  };

  it('exposes a labelled ban affordance when the author is not banned', () => {
    render(false);
    const button = fixture.debugElement.query(By.css('button'));
    expect(button.nativeElement.getAttribute('aria-label')).toBe('Ban griefer');
  });

  it('emits ban when the ban affordance is activated', () => {
    render(false);
    let banned = false;
    component.ban.subscribe(() => (banned = true));

    fixture.debugElement.query(By.css('button')).nativeElement.click();

    expect(banned).toBe(true);
  });

  it('exposes a labelled unban affordance when the author is banned', () => {
    render(true);
    const button = fixture.debugElement.query(By.css('button'));
    expect(button.nativeElement.getAttribute('aria-label')).toBe(
      'Unban griefer',
    );
  });

  it('emits unban when the unban affordance is activated', () => {
    render(true);
    let unbanned = false;
    component.unban.subscribe(() => (unbanned = true));

    fixture.debugElement.query(By.css('button')).nativeElement.click();

    expect(unbanned).toBe(true);
  });
});
