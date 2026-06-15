import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const guestGuard: CanMatchFn = () => {
  const authenticated = inject(AuthService).isAuthenticated();
  return authenticated ? inject(Router).createUrlTree(['/home']) : true;
};
