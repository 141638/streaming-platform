import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import { AvatarModule } from 'primeng/avatar';
import { TooltipModule } from 'primeng/tooltip';

/** Fixed dicebear seed used for system-generated notifications. */
const SYSTEM_AVATAR_URL =
  'https://api.dicebear.com/9.x/bottts-neutral/svg?seed=streaming-platform&backgroundColor=3b82f6';

@Component({
  selector: 'app-user-profile-picture',
  standalone: true,
  imports: [AvatarModule, TooltipModule],
  templateUrl: './user-profile-picture.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserProfilePictureComponent {
  private readonly router = inject(Router);

  /** Display name — empty string treated as system (no navigation). */
  public readonly username = input<string>('');
  /** Undefined → render fallback user icon. */
  public readonly avatarUrl = input<string>();
  /** Avatar size token forwarded to p-avatar. */
  public readonly size = input<'normal' | 'large'>('normal');
  /** True → render a system/cog avatar — no click, no user styling. */
  public readonly isSystem = input<boolean>(false);

  /** Resolved image URL: system → bottts dicebear; user → provided url or undefined. */
  public readonly imageSrc = computed<string | undefined>(() => {
    if (this.isSystem()) {
      return SYSTEM_AVATAR_URL;
    }
    return this.avatarUrl() || undefined;
  });

  /** Icon fallback when no image is available. System always has an image. */
  public readonly fallbackIcon = computed<string | undefined>(() => {
    if (this.isSystem()) {
      return undefined; // image covers it
    }
    return this.avatarUrl() ? undefined : 'pi pi-user';
  });

  /** Tooltip shown on hover. */
  public readonly tooltipLabel = computed<string>(() => {
    if (this.isSystem()) {
      return 'Streaming Platform — system notification';
    }
    const name = this.username();
    return name ? `@${name}` : '';
  });

  /**
   * Navigate to the sender's channel page.
   * No-op when {@link isSystem} is true or {@link username} is empty.
   */
  public onAvatarClick(): void {
    if (this.isSystem()) {
      return;
    }
    const name = this.username();
    if (name) {
      this.router.navigateByUrl(`/@${name}`);
    }
  }
}
