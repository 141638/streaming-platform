import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { Component } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { PasswordResetPage } from './password-reset.page';
import { PasswordResetResponseDto } from '../../../core/contracts/password-reset-response.dto';

@Component({ standalone: true, template: '' })
class StubComponent {}

describe('PasswordResetPage', () => {
  let component: PasswordResetPage;
  let fixture: ComponentFixture<PasswordResetPage>;
  let httpTesting: HttpTestingController;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PasswordResetPage, NoopAnimationsModule],
      providers: [
        provideRouter([
          { path: 'password-reset', component: PasswordResetPage },
          { path: 'login', component: StubComponent },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PasswordResetPage);
    component = fixture.componentInstance;
    httpTesting = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('creates the component', () => {
    expect(component).toBeTruthy();
  });

  it('shows error message when token is missing from URL', () => {
    const errorMessage = fixture.debugElement.query(
      By.css('p-message[severity="error"]'),
    );
    expect(errorMessage).toBeTruthy();
    expect(errorMessage.nativeElement.textContent).toContain(
      'Invalid reset link',
    );
  });

  it('shows the form when token is present', () => {
    component['token'].set('test-reset-token');
    fixture.detectChanges();

    const form = fixture.debugElement.query(By.css('form'));
    expect(form).toBeTruthy();
    const passwordInput = fixture.debugElement.query(By.css('#pr-password'));
    expect(passwordInput).toBeTruthy();
  });

  it('disables the submit button when form is invalid', () => {
    component['token'].set('test-reset-token');
    fixture.detectChanges();

    const button = fixture.debugElement.query(By.css('p-button'));
    expect(button).toBeTruthy();
    // Form starts invalid because fields are empty
    expect(button.attributes['ng-reflect-disabled']).toBe('true');
  });

  it('sends POST request and shows success message on 200', fakeAsync(() => {
    const navigateSpy = spyOn(router, 'navigateByUrl').and.returnValue(
      Promise.resolve(true),
    );
    component['token'].set('test-reset-token');
    fixture.detectChanges();

    // Fill out the form
    component['form'].controls.newPassword.setValue('newSecure123');
    component['form'].controls.confirmPassword.setValue('newSecure123');
    fixture.detectChanges();

    // Submit
    component['submit']();

    const req = httpTesting.expectOne('/api/auth/v1/password-reset/confirm');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.token).toBe('test-reset-token');
    expect(req.request.body.newPassword).toBe('newSecure123');

    const response: PasswordResetResponseDto = {
      status: 'ok',
      message: 'Password has been reset. You may now sign in.',
    };
    req.flush(response);

    tick();
    fixture.detectChanges();

    expect(component['successText']()).toBe(
      'Password has been reset. You may now sign in.',
    );
    expect(component['submitted']()).toBe(true);

    // Flush the 2-second redirect timeout
    tick(2500);
    expect(navigateSpy).toHaveBeenCalledWith('/login?reset=success');
  }));

  it('shows error message on 400 response', fakeAsync(() => {
    component['token'].set('bad-token');
    fixture.detectChanges();

    component['form'].controls.newPassword.setValue('newSecure123');
    component['form'].controls.confirmPassword.setValue('newSecure123');
    fixture.detectChanges();

    component['submit']();

    const req = httpTesting.expectOne('/api/auth/v1/password-reset/confirm');
    req.flush(
      { status: 'error', message: 'Invalid or expired reset token.' },
      { status: 400, statusText: 'Bad Request' },
    );

    tick();
    fixture.detectChanges();

    expect(component['errorText']()).toBe('Invalid or expired reset token.');
  }));
});
