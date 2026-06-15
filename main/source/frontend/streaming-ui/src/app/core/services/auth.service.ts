import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
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

  public readonly accessToken = this._accessToken.asReadonly();
  public readonly isAuthenticated = computed(() => this._accessToken() !== null);

  public login(request: LoginRequestDto): Observable<LoginResponseDto> {
    return this.http
      .post<LoginResponseDto>('/api/auth/v1/login', request)
      .pipe(tap((res) => this.persistSession(res)));
  }

  public logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    this._accessToken.set(null);
    this.router.navigateByUrl('/login');
  }

  private persistSession(response: LoginResponseDto): void {
    localStorage.setItem(TOKEN_KEY, response.accessToken);
    this._accessToken.set(response.accessToken);
  }
}
