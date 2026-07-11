import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { BanUserDialogComponent } from './ban-user-dialog.component';

interface Confirmation {
  reason: string | null;
  durationSeconds: number | null;
}

describe('BanUserDialogComponent', () => {
  let fixture: ComponentFixture<BanUserDialogComponent>;
  let component: BanUserDialogComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [BanUserDialogComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(BanUserDialogComponent);
    component = fixture.componentInstance;
  });

  const open = (): void => {
    fixture.componentRef.setInput('visible', true);
    fixture.componentRef.setInput('target', {
      subject: 'auth0|abcdef1234567890',
      username: 'griefer',
    });
    fixture.detectChanges();
  };

  it('defaults the duration to 24 hours (86400s)', () => {
    open();
    expect(component['form'].controls.duration.value).toBe(86400);
  });

  it('resolves the target display name', () => {
    open();
    expect(component['targetName']()).toBe('griefer');
  });

  it('emits confirm with a null reason when the field is blank', () => {
    open();
    let payload: Confirmation | undefined;
    component.confirm.subscribe((p) => (payload = p));

    component['onConfirm']();

    expect(payload).toEqual({ reason: null, durationSeconds: 86400 });
  });

  it('emits confirm with a trimmed reason and the selected duration', () => {
    open();
    component['form'].controls.reason.setValue('   repeated spam   ');
    component['form'].controls.duration.setValue(3600);
    let payload: Confirmation | undefined;
    component.confirm.subscribe((p) => (payload = p));

    component['onConfirm']();

    expect(payload).toEqual({ reason: 'repeated spam', durationSeconds: 3600 });
  });

  it('emits cancel when dismissed', () => {
    open();
    let cancelled = false;
    component.cancel.subscribe(() => (cancelled = true));

    component['onCancel']();

    expect(cancelled).toBe(true);
  });

  it('resets a dirty form when the dialog re-opens', () => {
    open();
    component['form'].controls.reason.setValue('stale note');
    component['form'].controls.duration.setValue(604800);

    fixture.componentRef.setInput('visible', false);
    fixture.detectChanges();
    fixture.componentRef.setInput('visible', true);
    fixture.detectChanges();

    expect(component['form'].controls.reason.value).toBe('');
    expect(component['form'].controls.duration.value).toBe(86400);
  });
});
