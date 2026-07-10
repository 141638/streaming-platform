import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { SocialLinkDto } from '../../../core/contracts/social-link.dto';

/** Map platform keys to PrimeIcons. */
const PLATFORM_ICONS: Record<string, string> = {
  twitter: 'pi pi-twitter',
  youtube: 'pi pi-youtube',
  instagram: 'pi pi-instagram',
  discord: 'pi pi-discord',
  tiktok: 'pi pi-video',
  website: 'pi pi-globe',
};

@Component({
  selector: 'app-social-links',
  standalone: true,
  imports: [ButtonModule, TooltipModule],
  templateUrl: './social-links.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SocialLinksComponent {
  public readonly links = input.required<readonly SocialLinkDto[] | null>();

  protected iconFor(platform: string): string {
    return PLATFORM_ICONS[platform] ?? 'pi pi-link';
  }

  protected labelFor(platform: string): string {
    return platform.charAt(0).toUpperCase() + platform.slice(1);
  }
}
