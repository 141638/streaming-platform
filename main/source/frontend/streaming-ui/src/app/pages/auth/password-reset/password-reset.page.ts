import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { finalize } from 'rxjs';
import { PasswordResetConfirmRequestDto } from '../../../core/contracts/password-reset-confirm-request.dto';
import { PasswordResetResponseDto } from '../../../core/contracts/password-reset-response.dto';

@Component({
  selector: 'app-password-reset-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterModule,
    InputTextModule,
    ButtonModule,
    MessageModule,
  ],
  templateUrl: './password-reset.page.html',
  styleUrl: './password-reset.page.scss',
})
export class PasswordResetPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  protected readonly token = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly errorText = signal<string | null>(null);
  protected readonly successText = signal<string | null>(null);
  protected readonly submitted = signal(false);

  protected readonly form = this.fb.group(
    {
      newPassword: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: this.passwordsMatch },
  );

  public ngOnInit(): void {
    const paramToken = this.route.snapshot.queryParamMap.get('token');
    if (!paramToken || paramToken.trim().length === 0) {
      this.errorText.set(
        'Invalid reset link. Please request a new one.',
      );
    } else {
      this.token.set(paramToken.trim());
    }
  }

  protected submit(): void {
    if (this.form.invalid || !this.token()) {
      return;
    }
    this.errorText.set(null);
    this.successText.set(null);
    this.loading.set(true);

    const body: PasswordResetConfirmRequestDto = {
      token: this.token()!,
      newPassword: this.form.controls.newPassword.value ?? '',
    };

    this.http
      .post<PasswordResetResponseDto>(
        '/api/auth/v1/password-reset/confirm',
        body,
      )
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (res) => {
          this.submitted.set(true);
          this.successText.set(res.message);
          setTimeout(() => {
            this.router.navigateByUrl('/login?reset=success');
          }, 2000);
        },
        error: (err: unknown) => {
          if (err instanceof HttpErrorResponse) {
            const msg =
              err.error?.message ??
              'Unable to reset password. Please try again.';
            this.errorText.set(msg);
          } else {
            this.errorText.set(
              'Unable to reach the server. Check your connection and try again.',
            );
          }
        },
      });
  }

  private passwordsMatch(control: AbstractControl): ValidationErrors | null {
    const pw = control.get('newPassword')?.value;
    const confirm = control.get('confirmPassword')?.value;
    if (pw && confirm && pw !== confirm) {
      return { passwordsMismatch: true };
    }
    return null;
  }
}
