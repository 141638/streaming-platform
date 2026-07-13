import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';
import { ChannelAboutResponseDto } from '../../../core/contracts/channel-about-response.dto';
import { SocialLinkDto } from '../../../core/contracts/social-link.dto';
import { AuthService } from '../../../core/services/auth.service';
import { StreamService } from '../../../core/services/stream.service';
import { SocialLinksComponent } from '../../../shared/molecules/social-links/social-links.component';

interface PlatformOption {
  readonly label: string;
  readonly value: string;
}

const PLATFORM_OPTIONS: readonly PlatformOption[] = [
  { label: 'Twitter', value: 'twitter' },
  { label: 'YouTube', value: 'youtube' },
  { label: 'Instagram', value: 'instagram' },
  { label: 'Discord', value: 'discord' },
  { label: 'TikTok', value: 'tiktok' },
  { label: 'Website', value: 'website' },
];

@Component({
  selector: 'app-channel-about-tab',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    SelectModule,
    TextareaModule,
    TooltipModule,
    SocialLinksComponent,
  ],
  templateUrl: './about-tab.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AboutTabComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly authService = inject(AuthService);
  private readonly streamService = inject(StreamService);

  private readonly username = this.route.parent!.snapshot.paramMap.get('username')!;

  protected readonly channel = signal<ChannelAboutResponseDto | null>(null);
  protected readonly loading = signal(true);

  protected readonly isOwner = computed(
    () => this.authService.myUsername() === this.username,
  );

  // Bio editing
  protected readonly editingBio = signal(false);
  protected readonly bioDraft = signal('');
  protected readonly savingBio = signal(false);

  // Social links editing
  protected readonly editingLinks = signal(false);
  protected readonly linksDraft = signal<SocialLinkDto[]>([]);
  protected readonly newLinkPlatform = signal<PlatformOption | null>(null);
  protected readonly newLinkUrl = signal('');
  protected readonly savingLinks = signal(false);
  protected readonly platformOptions: PlatformOption[] = [...PLATFORM_OPTIONS];

  public ngOnInit(): void {
    this.streamService
      .getChannelAbout(this.username)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (res) =>
          this.channel.set({
            ...res,
            socialLinks: res.socialLinks ?? [],
            stats: res.stats ?? {
              totalStreams: 0,
              totalHoursStreamed: 0,
              topCategory: null,
              firstStreamedAt: null,
              categoryBreakdown: [],
            },
          }),
        error: (err: unknown) => {
          if (err instanceof DOMException && err.name === 'AbortError') return;
          this.channel.set(null);
        },
      });
  }

  // Bio
  protected startEditingBio(): void {
    const current = this.channel();
    this.bioDraft.set(current?.bio ?? '');
    this.editingBio.set(true);
  }

  protected cancelEditingBio(): void {
    this.editingBio.set(false);
  }

  protected saveBio(): void {
    const ch = this.channel();
    if (!ch) return;
    this.savingBio.set(true);
    this.streamService
      .updateProfile(this.username, this.bioDraft(), ch.socialLinks)
      .pipe(finalize(() => this.savingBio.set(false)))
      .subscribe({
        next: () => {
          this.channel.update((c) => (c ? { ...c, bio: this.bioDraft() } : c));
          this.editingBio.set(false);
        },
      });
  }

  // Social links
  protected startEditingLinks(): void {
    const current = this.channel();
    this.linksDraft.set(current?.socialLinks ? [...current.socialLinks] : []);
    this.editingLinks.set(true);
  }

  protected cancelEditingLinks(): void {
    this.editingLinks.set(false);
  }

  protected addLinkDraft(): void {
    const platform = this.newLinkPlatform();
    const url = this.newLinkUrl().trim();
    if (!platform || !url) return;
    if (this.linksDraft().some((l) => l.platform === platform.value)) return;
    this.linksDraft.update((links) => [
      ...links,
      { platform: platform.value, url },
    ]);
    this.newLinkPlatform.set(null);
    this.newLinkUrl.set('');
  }

  protected removeLinkDraft(platform: string): void {
    this.linksDraft.update((links) =>
      links.filter((l) => l.platform !== platform),
    );
  }

  protected saveLinks(): void {
    const ch = this.channel();
    if (!ch) return;
    this.savingLinks.set(true);
    this.streamService
      .updateProfile(this.username, ch.bio ?? '', this.linksDraft())
      .pipe(finalize(() => this.savingLinks.set(false)))
      .subscribe({
        next: () => {
          this.channel.update((c) =>
            c ? { ...c, socialLinks: this.linksDraft() } : c,
          );
          this.editingLinks.set(false);
        },
      });
  }

  protected streamingSince(iso: string | null): string {
    if (!iso) return '';
    return new Date(iso).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'long',
    });
  }
}
