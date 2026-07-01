import { Component, inject } from '@angular/core';
import { Router, RouterModule, RouterOutlet } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { AvatarModule } from 'primeng/avatar';
import { ButtonModule } from 'primeng/button';
import { MenuModule } from 'primeng/menu';
import { ToolbarModule } from 'primeng/toolbar';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterModule, ToolbarModule, ButtonModule, MenuModule, AvatarModule],
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {
  private readonly router = inject(Router);
  protected readonly authService = inject(AuthService);

  protected readonly createMenuItems: MenuItem[] = [
    {
      label: 'Upload VOD',
      icon: 'pi pi-upload',
      disabled: true,
    },
    {
      label: 'Go Live',
      icon: 'pi pi-video',
      command: () => this.router.navigateByUrl('/streams/create'),
    },
  ];

  protected readonly userMenuItems: MenuItem[] = [
    {
      label: 'Channel',
      icon: 'pi pi-play',
      command: () => this.router.navigateByUrl('/channel'),
    },
    {
      label: 'Creator Dashboard',
      icon: 'pi pi-chart-bar',
      disabled: true,
    },
    {
      label: 'Stream Summary',
      icon: 'pi pi-list',
      disabled: true,
    },
    { separator: true },
    {
      label: 'Subscriptions',
      icon: 'pi pi-star',
      disabled: true,
    },
    {
      label: 'Wallet',
      icon: 'pi pi-wallet',
      disabled: true,
    },
    { separator: true },
    {
      label: 'Settings',
      icon: 'pi pi-cog',
      disabled: true,
    },
    {
      label: 'Language',
      icon: 'pi pi-globe',
      disabled: true,
    },
    {
      label: 'Theme',
      icon: 'pi pi-palette',
      disabled: true,
    },
    { separator: true },
    {
      label: 'Logout',
      icon: 'pi pi-sign-out',
      command: () => this.authService.logout(),
    },
  ];
}
