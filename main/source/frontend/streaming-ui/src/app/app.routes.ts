import { Routes, UrlSegment } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { guestGuard } from './core/guards/guest.guard';

/**
 * Match {@code /@:username} URLs — Angular's standard path parser doesn't
 * handle {@code @} as a literal segment character, so we use a custom matcher
 * that strips the leading {@code @} and exposes the remainder as the
 * {@code username} route parameter.
 */
function channelMatcher(segments: UrlSegment[]) {
  if (segments.length === 1 && segments[0].path.startsWith('@')) {
    const username = segments[0].path.slice(1);
    if (username.length === 0) {
      return null;
    }
    return {
      consumed: segments,
      posParams: { username: new UrlSegment(username, {}) },
    };
  }
  return null;
}

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
        path: 'dashboard/streams',
        loadComponent: () =>
          import('./pages/streams/stream-dashboard/stream-dashboard.page').then(
            (m) => m.StreamDashboardPage,
          ),
      },
      {
        path: 'channel/:id',
        loadComponent: () =>
          import('./pages/streams/stream-detail/stream-detail.page').then(
            (m) => m.StreamDetailPage,
          ),
      },
      { path: 'channel', redirectTo: 'dashboard/streams', pathMatch: 'full' },
      {
        path: 'chat/:roomKey',
        loadComponent: () =>
          import('./pages/chat/chat-room.page').then((m) => m.ChatRoomPage),
      },
      {
        matcher: channelMatcher,
        loadComponent: () =>
          import('./pages/channel/channel.page').then((m) => m.ChannelPage),
      },
      { path: '', redirectTo: 'home', pathMatch: 'full' },
    ],
  },
];
