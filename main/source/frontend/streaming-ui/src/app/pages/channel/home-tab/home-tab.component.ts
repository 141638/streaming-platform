import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { finalize } from 'rxjs';
import { ChannelHomeResponseDto } from '../../../core/contracts/channel-home-response.dto';
import { StreamService } from '../../../core/services/stream.service';
import { CategoryStripComponent } from '../../../shared/molecules/category-strip/category-strip.component';
import { PlaylistRailComponent } from '../../../shared/organisms/playlist-rail/playlist-rail.component';
import { SessionRailComponent } from '../../../shared/organisms/session-rail/session-rail.component';

@Component({
  selector: 'app-channel-home-tab',
  standalone: true,
  imports: [SessionRailComponent, CategoryStripComponent, PlaylistRailComponent],
  templateUrl: './home-tab.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class HomeTabComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly streamService = inject(StreamService);

  private readonly username = this.route.parent!.snapshot.paramMap.get('username')!;

  protected readonly channel = signal<ChannelHomeResponseDto | null>(null);
  protected readonly loading = signal(true);

  public ngOnInit(): void {
    this.streamService
      .getChannelHome(this.username)
      .pipe(
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (res) => this.channel.set(res),
        error: (err: unknown) => {
          if (err instanceof DOMException && err.name === 'AbortError') return;
          this.channel.set(null);
        },
      });
  }
}
