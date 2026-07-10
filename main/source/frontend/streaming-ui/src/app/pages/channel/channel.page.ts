import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TabsModule } from 'primeng/tabs';
import { TextareaModule } from 'primeng/textarea';
import { finalize } from 'rxjs';
import { ChannelResponseDto } from '../../core/contracts/channel-response.dto';
import { SocialLinkDto } from '../../core/contracts/social-link.dto';
import { AuthService } from '../../core/services/auth.service';
import { StreamService } from '../../core/services/stream.service';
import { CategoryStripComponent } from '../../shared/molecules/category-strip/category-strip.component';
import { SocialLinksComponent } from '../../shared/molecules/social-links/social-links.component';
import { ChannelHeaderComponent } from '../../shared/organisms/channel-header/channel-header.component';
import { PlaylistRailComponent } from '../../shared/organisms/playlist-rail/playlist-rail.component';
import { SessionRailComponent } from '../../shared/organisms/session-rail/session-rail.component';

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
  selector: 'app-channel-page',
  standalone: true,
  imports: [
    RouterModule,
    FormsModule,
    ButtonModule,
    InputTextModule,
    SelectModule,
    TabsModule,
    TextareaModule,
    ChannelHeaderComponent,
    SessionRailComponent,
    PlaylistRailComponent,
    CategoryStripComponent,
    SocialLinksComponent,
  ],
  templateUrl: './channel.page.html',
  styleUrl: './channel.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelPage implements OnInit {
  /** Bound from route param {@code :username} via {@code withComponentInputBinding()}. */
  public readonly username = input.required<string>();

  private readonly authService = inject(AuthService);
  private readonly streamService = inject(StreamService);
  private readonly destroyRef = inject(DestroyRef);

  // ── State ───────────────────────────────────────────────────────────────

  protected readonly channel = signal<ChannelResponseDto | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly isOwner = computed(
    () => this.authService.myUsername() === this.username(),
  );

  // ── Bio editing (owner-only, About tab) ──────────────────────────────────

  protected readonly editingBio = signal(false);
  protected readonly bioDraft = signal('');
  protected readonly savingBio = signal(false);

  // ── Social links editing (owner-only, About tab) ─────────────────────────

  protected readonly editingLinks = signal(false);
  protected readonly linksDraft = signal<SocialLinkDto[]>([]);
  protected readonly newLinkPlatform = signal<PlatformOption | null>(null);
  protected readonly newLinkUrl = signal('');
  protected readonly savingLinks = signal(false);
  protected readonly platformOptions: PlatformOption[] = [...PLATFORM_OPTIONS];

  // ── Lifecycle ───────────────────────────────────────────────────────────

  public ngOnInit(): void {
    this.streamService
      .getChannel(this.username())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (res) =>
          this.channel.set({
            ...res,
            socialLinks: res.socialLinks ?? [],
            stats: res.stats ?? { totalStreams: 0, totalHoursStreamed: 0, topCategory: null },
          }),
        error: () => this.error.set('Failed to load channel'),
      });
  }

  // ── Bio editing ─────────────────────────────────────────────────────────

  protected startEditingBio(): void {
    const current = this.channel();
    this.bioDraft.set(current?.bio ?? '');
    this.editingBio.set(true);
  }

  protected cancelEditingBio(): void {
    this.editingBio.set(false);
    this.bioDraft.set('');
  }

  protected saveBio(): void {
    const ch = this.channel();
    if (!ch) return;
    this.savingBio.set(true);
    this.streamService
      .updateProfile(this.username(), this.bioDraft(), ch.socialLinks)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.savingBio.set(false)),
      )
      .subscribe({
        next: () => {
          this.channel.update((c) =>
            c ? { ...c, bio: this.bioDraft() } : c,
          );
          this.editingBio.set(false);
        },
        error: () => {
          // Keep the edit form open so the user doesn't lose their draft
        },
      });
  }

  // ── Social links editing ────────────────────────────────────────────────

  protected startEditingLinks(): void {
    const current = this.channel();
    this.linksDraft.set(current?.socialLinks ? [...current.socialLinks] : []);
    this.newLinkPlatform.set(null);
    this.newLinkUrl.set('');
    this.editingLinks.set(true);
  }

  protected cancelEditingLinks(): void {
    this.editingLinks.set(false);
    this.linksDraft.set([]);
    this.newLinkPlatform.set(null);
    this.newLinkUrl.set('');
  }

  protected addLinkDraft(): void {
    const platform = this.newLinkPlatform();
    const url = this.newLinkUrl().trim();
    if (!platform || !url) return;

    // Don't allow duplicate platforms
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
      .updateProfile(this.username(), ch.bio ?? '', this.linksDraft())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.savingLinks.set(false)),
      )
      .subscribe({
        next: () => {
          this.channel.update((c) =>
            c ? { ...c, socialLinks: this.linksDraft() } : c,
          );
          this.editingLinks.set(false);
        },
        error: () => {
          // Keep the edit form open
        },
      });
  }
}
