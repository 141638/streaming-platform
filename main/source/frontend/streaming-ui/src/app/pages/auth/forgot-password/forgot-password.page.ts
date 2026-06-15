import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { finalize } from 'rxjs';
import { PasswordResetRequestDto } from '../../../core/contracts/password-reset-request.dto';
import { PasswordResetResponseDto } from '../../../core/contracts/password-reset-response.dto';

@Component({
  selector: 'app-forgot-password-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterModule,
    InputTextModule,
    ButtonModule,
    MessageModule,
  ],
  templateUrl: './forgot-password.page.html',
  styleUrl: './forgot-password.page.scss',
})
export class ForgotPasswordPage {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  protected readonly loading = signal(false);
  protected readonly errorText = signal<string | null>(null);
  protected readonly submitted = signal(false);

  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  /**
   * Sends a password-reset request for the given email.
   * On success the form is replaced with a confirmation message;
   * the API endpoint is a placeholder and will fail gracefully until implemented.
   */
  protected submit(): void {
    if (this.form.invalid) {
      return;
    }
    this.errorText.set(null);
    this.loading.set(true);

    const email = this.form.controls.email.value ?? '';

    const body: PasswordResetRequestDto = { email };

    this.http
      .post<PasswordResetResponseDto>('/api/auth/v1/password-reset/request', body)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.submitted.set(true),
        error: (err: unknown) => {
          const message =
            err instanceof HttpErrorResponse && err.status >= 500
              ? 'Something went wrong. Please try again later.'
              : 'Unable to reach the server. Check your connection and try again.';
          this.errorText.set(message);
        },
      });
  }
}
