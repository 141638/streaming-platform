import { Component } from '@angular/core';
import { RouterModule, RouterOutlet } from '@angular/router';
import { ToolbarModule } from 'primeng/toolbar';

@Component({
  selector: 'app-app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterModule, ToolbarModule],
  templateUrl: './app-shell.component.html',
})
export class AppShellComponent {}
