import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SkeletonModule } from 'primeng/skeleton';

/**
 * Reusable horizontal scroll rail with header (title + optional "See more"
 * link), drag-to-scroll, float chevrons, and {@code ng-content} for flexible
 * card projection.
 *
 * <p>Each rail manages its own scroll state independently — multiple rails
 * on the same page don't interfere.
 */
@Component({
  selector: 'app-rail',
  standalone: true,
  imports: [RouterModule, ButtonModule, SkeletonModule],
  templateUrl: './rail.component.html',
  styleUrl: './rail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RailComponent {
  /** Section title shown in the header (e.g. "Recent Broadcasts"). */
  public readonly title = input.required<string>();

  /** RouterLink for the "See more" link. {@code null} hides the link. */
  public readonly seeMoreLink = input<string | null>(null);

  /** Optional query params attached to the "See more" router link. */
  public readonly seeMoreQueryParams = input<Record<string, string> | null>(null);

  /** When {@code true}, shows skeleton cards instead of content. */
  public readonly loading = input(false);

  /** Label shown when no items are projected and not loading. */
  public readonly emptyLabel = input('Nothing here yet');

  private readonly railRef = viewChild.required<ElementRef<HTMLElement>>('rail');

  protected readonly canScrollLeft = signal(false);
  protected readonly canScrollRight = signal(false);

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
}
