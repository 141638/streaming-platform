import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { TokenErrorResponse } from '../contracts/login-response.dto';
import { AuthService } from '../services/auth.service';

const SAFE_METHODS = new Set<string>(['GET', 'HEAD', 'OPTIONS']);

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);

  const authReq = cloneWithAuth(req, authService.accessToken());

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401) {
        return throwError(() => error);
      }

      // Never intercept refresh or logout calls — infinite-loop guard
      if (req.url.includes('/api/auth/v1/token/refresh')
          || req.url.includes('/api/auth/v1/logout')) {
        return throwError(() => error);
      }

      const body = error.error as TokenErrorResponse;
      const isExpired = body?.error_code === 'token_expired';

      if (!isExpired) {
        authService.logout();
        return throwError(() => error);
      }

      // Token expired — attempt single-flight refresh
      return authService.refresh().pipe(
        switchMap(() => {
          if (SAFE_METHODS.has(req.method)) {
            return next(cloneWithAuth(req, authService.accessToken()));
          }
          // Unsafe method with idempotency key — safe to retry because
          // the gateway will return the cached response on duplicates.
          if (req.headers.has('Idempotency-Key')) {
            return next(cloneWithAuth(req, authService.accessToken()));
          }
          // Unsafe method without idempotency key — caller must retry
          return throwError(() => error);
        }),
        catchError((err: unknown) => {
          // err === error → propagated original (unsafe method). Don't logout.
          // err !== error → refresh itself failed. Logout.
          if (err !== error) {
            authService.logout();
          }
          return throwError(() => err);
        }),
      );
    }),
  );
};

/** Clone a request, attaching the Bearer token if present. */
function cloneWithAuth(
  req: HttpRequest<unknown>,
  token: string | null,
): HttpRequest<unknown> {
  if (!token) {
    return req;
  }
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}
