import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterModule } from '@angular/router';
import { CardModule } from 'primeng/card';
import { ButtonModule } from 'primeng/button';
import { SkeletonModule } from 'primeng/skeleton';
import { TooltipModule } from 'primeng/tooltip';
import { StreamSummaryResponseDto } from '../../../core/contracts/stream-summary-response.dto';
import { StreamStatusBadgeComponent } from '../../molecules/stream-status-badge/stream-status-badge.component';

/** Derive a stable hue from a category name for the placeholder thumbnail. */
function categoryHue(name: string): number {
  let hash = 0;
  for (let i = 0; i < name?.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return Math.abs(hash % 360);
}

@Component({
  selector: 'app-session-rail',
  standalone: true,
  imports: [
    RouterModule,
    CardModule,
    ButtonModule,
    SkeletonModule,
    TooltipModule,
    StreamStatusBadgeComponent,
  ],
  templateUrl: './session-rail.component.html',
  styleUrl: './session-rail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionRailComponent {
  public readonly sessions = input.required<readonly StreamSummaryResponseDto[]>();
  public readonly loading = input(false);

  private readonly railRef = viewChild.required<ElementRef<HTMLElement>>('rail');

  protected readonly canScrollLeft = signal(false);
  protected readonly canScrollRight = signal(true);

  // ── Drag scroll ─────────────────────────────────────────────────────────

  private dragging = false;
  private startX = 0;
  private startScroll = 0;

  protected onMouseDown(event: MouseEvent): void {
    this.dragging = true;
    this.startX = event.clientX;
    this.startScroll = this.railRef().nativeElement.scrollLeft;
    event.preventDefault();
  }

  protected onMouseMove(event: MouseEvent): void {
    if (!this.dragging) {
      return;
    }
    const dx = this.startX - event.clientX;
    this.railRef().nativeElement.scrollLeft = this.startScroll + dx;
  }

  protected onMouseUp(): void {
    this.dragging = false;
    this.updateScrollState();
  }

  // ── Chevron buttons ─────────────────────────────────────────────────────

  protected scrollLeft(): void {
    const el = this.railRef().nativeElement;
    el.scrollBy({ left: -el.clientWidth * 0.6, behavior: 'smooth' });
    setTimeout(() => this.updateScrollState(), 300);
  }

  protected scrollRight(): void {
    const el = this.railRef().nativeElement;
    el.scrollBy({ left: el.clientWidth * 0.6, behavior: 'smooth' });
    setTimeout(() => this.updateScrollState(), 300);
  }

  private updateScrollState(): void {
    const el = this.railRef().nativeElement;
    this.canScrollLeft.set(el.scrollLeft > 4);
    this.canScrollRight.set(
      el.scrollLeft < el.scrollWidth - el.clientWidth - 4,
    );
  }

  protected onScroll(): void {
    this.updateScrollState();
  }

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

  /** Full UTC timestamp for the hover tooltip — same pattern as chat timestamps. */
  protected tooltipDate(iso: string): string {
    const d = new Date(iso);
    return d.toISOString().replace('T', ' ').substring(0, 19) + ' UTC';
  }

  /**
   * Display name for a session's broadcaster.
   * Falls back to "Unknown" for backfill-gap rows (pre-V7 sessions).
   */
  protected channelName(session: StreamSummaryResponseDto): string {
    return session.broadcasterUsername ?? 'Unknown';
  }
}
