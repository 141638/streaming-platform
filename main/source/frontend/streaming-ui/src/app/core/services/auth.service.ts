import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize, Observable, share, tap } from 'rxjs';
import { LoginRequestDto } from '../contracts/login-request.dto';
import { LoginResponseDto } from '../contracts/login-response.dto';

const TOKEN_KEY = 'streaming_access_token';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly _accessToken = signal<string | null>(
    localStorage.getItem(TOKEN_KEY),
  );

  /** Single-flight guard: only one refresh HTTP call at a time. */
  private _refreshInProgress: Observable<LoginResponseDto> | null = null;

  public readonly accessToken = this._accessToken.asReadonly();
  public readonly isAuthenticated = computed(() => this._accessToken() !== null);

  // ── Public API ──────────────────────────────────────────────────────────

  public login(request: LoginRequestDto): Observable<LoginResponseDto> {
    return this.http
      .post<LoginResponseDto>('/api/auth/v1/login', request)
      .pipe(tap((res) => this.persistSession(res)));
  }

  /**
   * Exchange the refresh-token cookie for new access + refresh tokens.
   * Multiple concurrent callers share a single HTTP call via {@code share()}.
   */
  public refresh(): Observable<LoginResponseDto> {
    if (this._refreshInProgress) {
      return this._refreshInProgress;
    }

    this._refreshInProgress = this.http
      .post<LoginResponseDto>('/api/auth/v1/token/refresh', {})
      .pipe(
        tap((res) => this.persistSession(res)),
        finalize(() => {
          this._refreshInProgress = null;
        }),
        share(),
      );

    return this._refreshInProgress;
  }

  public logout(): void {
    this._refreshInProgress = null;
    localStorage.removeItem(TOKEN_KEY);
    this._accessToken.set(null);
    this.router.navigateByUrl('/login');
  }

  // ── Private helpers ─────────────────────────────────────────────────────

  private persistSession(response: LoginResponseDto): void {
    localStorage.setItem(TOKEN_KEY, response.accessToken);
    this._accessToken.set(response.accessToken);
  }
}
