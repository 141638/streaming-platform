import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';

/** Stable pseudo-subscriber count derived from the category name. */
function pseudoSubscriberCount(name: string): string {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  const n = Math.abs(hash % 50_000) + 500;
  if (n >= 10_000) {
    return (n / 1_000).toFixed(1) + 'K';
  }
  if (n >= 1_000) {
    return (n / 1_000).toFixed(2) + 'K';
  }
  return n.toString();
}

/** Derive a stable hue from a category name for the placeholder thumbnail. */
function categoryHue(name: string): number {
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  return Math.abs(hash % 360);
}

interface CategoryDisplay {
  readonly name: string;
  readonly hue: number;
  readonly subscribers: string;
  readonly thumbnailStyle: Record<string, string>;
}

@Component({
  selector: 'app-category-strip',
  standalone: true,
  imports: [ButtonModule, TooltipModule],
  templateUrl: './category-strip.component.html',
  styleUrl: './category-strip.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CategoryStripComponent {
  public readonly categories = input.required<readonly string[]>();

  protected readonly displayCategories = computed<readonly CategoryDisplay[]>(() =>
    this.categories().map((name) => {
      const hue = categoryHue(name);
      return {
        name,
        hue,
        subscribers: pseudoSubscriberCount(name),
        thumbnailStyle: {
          background: `linear-gradient(135deg, oklch(55% 0.14 ${hue}), oklch(40% 0.12 ${(hue + 40) % 360}))`,
        },
      };
    }),
  );
}
