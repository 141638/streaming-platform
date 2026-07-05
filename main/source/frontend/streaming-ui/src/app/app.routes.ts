import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';

export const routes: Routes = [
  {
    path: 'login',
    canMatch: [guestGuard],
    loadComponent: () =>
      import('./pages/auth/login/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'forgot-password',
    canMatch: [guestGuard],
    loadComponent: () =>
      import('./pages/auth/forgot-password/forgot-password.page').then(
        (m) => m.ForgotPasswordPage,
      ),
  },
  {
    path: 'password-reset',
    canMatch: [guestGuard],
    loadComponent: () =>
      import('./pages/auth/password-reset/password-reset.page').then(
        (m) => m.PasswordResetPage,
      ),
  },
  { path: '', redirectTo: '/login', pathMatch: 'full' },
  {
    path: '',
    canMatch: [authGuard],
    loadComponent: () =>
      import('./pages/app/app-shell.component').then(
        (m) => m.AppShellComponent,
      ),
    children: [
      {
        path: 'home',
        loadComponent: () =>
          import('./pages/home/home.page').then((m) => m.HomePage),
      },
      {
        path: 'streams/create',
        loadComponent: () =>
          import('./pages/streams/stream-create/stream-create.page').then(
            (m) => m.StreamCreatePage,
          ),
      },
      {
        path: 'channel',
        loadComponent: () =>
          import('./pages/streams/channel/channel.page').then(
            (m) => m.ChannelPage,
          ),
      },
      {
        path: 'chat/:roomKey',
        loadComponent: () =>
          import('./pages/chat/chat-room.page').then((m) => m.ChatRoomPage),
      },
      { path: '', redirectTo: 'home', pathMatch: 'full' },
    ],
  },
];
