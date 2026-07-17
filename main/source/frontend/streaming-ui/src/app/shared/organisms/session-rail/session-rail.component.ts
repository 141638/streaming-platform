import { DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  input,
} from '@angular/core';
import { RouterModule } from '@angular/router';
import { TooltipModule } from 'primeng/tooltip';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';
import { RailComponent } from '../../molecules/rail/rail.component';
import { StreamStatusBadgeComponent } from '../../molecules/stream-status-badge/stream-status-badge.component';

/** Derive a stable hue from a category name for the placeholder thumbnail. */
function categoryHue(name: string): number {
  let hash = 0;
  for (let i = 0; i < name?.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return Math.abs(hash % 360);
}

/**
 * A rail of session cards for the channel Home tab. Delegates scroll
 * behaviour and header to {@link RailComponent}; only owns card rendering.
 */
@Component({
  selector: 'app-session-rail',
  standalone: true,
  imports: [
    RouterModule,
    TooltipModule,
    RailComponent,
    StreamStatusBadgeComponent,
    DecimalPipe,
  ],
  templateUrl: './session-rail.component.html',
  styleUrl: './session-rail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionRailComponent {
  public readonly sessions = input.required<readonly StreamSummaryResponseDto[]>();
  public readonly loading = input(false);

  // ── Display helpers ─────────────────────────────────────────────────────

  protected thumbnailUrl(session: StreamSummaryResponseDto): string {
    return session.thumbnailUrl ?? 'img/stream-placeholder.svg';
  }

  protected categoryThumbnailStyle(category: string): Record<string, string> {
    const h = categoryHue(category);
    return { background: `oklch(50% 0.14 ${h})` };
  }

  protected categoryInitial(category: string): string {
    return (category?.charAt(0) ?? '').toUpperCase();
  }

  /** Format a short date label (e.g. "Dec 14"). */
  protected shortDate(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
    });
  }

  /** Full UTC timestamp for the hover tooltip. */
  protected tooltipDate(iso: string): string {
    const d = new Date(iso);
    return d.toISOString().replace('T', ' ').substring(0, 19) + ' UTC';
  }

  protected channelName(session: StreamSummaryResponseDto): string {
    return session.broadcasterUsername ?? 'Unknown';
  }
}
