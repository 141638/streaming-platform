import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const authGuard: CanMatchFn = () => {
  const authenticated = inject(AuthService).isAuthenticated();
  return authenticated ? true : inject(Router).createUrlTree(['/login']);
};
