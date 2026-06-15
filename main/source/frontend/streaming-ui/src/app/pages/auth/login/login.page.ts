import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    RouterModule,
    InputTextModule,
    ButtonModule,
    MessageModule,
  ],
  templateUrl: './login.page.html',
  styleUrl: './login.page.scss',
})
export class LoginPage {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  protected readonly loading = signal(false);
  protected readonly errorText = signal<string | null>(null);

  protected readonly form = this.fb.group({
    username: ['', [Validators.required]],
    password: ['', [Validators.required]],
  });

  /**
   * Submits the login form, persists the session on success,
   * and navigates to the authenticated home page.
   */
  protected submit(): void {
    if (this.form.invalid) {
      return;
    }
    this.errorText.set(null);
    this.loading.set(true);

    const body = {
      username: this.form.controls.username.value ?? '',
      password: this.form.controls.password.value ?? '',
    };

    this.authService
      .login(body)
      .pipe(
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.router.navigateByUrl('/home'),
        error: (err: unknown) => {
          const message =
            err instanceof HttpErrorResponse && err.status === 401
              ? 'Invalid username or password'
              : 'Unable to reach the server. Check your connection and try again.';
          this.errorText.set(message);
        },
      });
  }
}
