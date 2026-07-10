import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CardModule } from 'primeng/card';

@Component({
  selector: 'app-playlist-rail',
  standalone: true,
  imports: [CardModule],
  templateUrl: './playlist-rail.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlaylistRailComponent {}
