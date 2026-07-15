import { Component, DestroyRef, inject, signal } from '@angular/core';
import {
  FormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { ChipsModule } from 'primeng/chips';
import { DatePickerModule } from 'primeng/datepicker';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { ToggleButtonModule } from 'primeng/togglebutton';
import { FormsModule } from '@angular/forms';
import { StreamService } from '../../../core/services/stream.service';
import { CategoryResponseDto } from '../../../core/contracts/category-response.dto';

@Component({
  selector: 'app-stream-create',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    FormsModule,
    CardModule,
    ButtonModule,
    InputTextModule,
    InputNumberModule,
    MessageModule,
    SelectModule,
    ChipsModule,
    DatePickerModule,
    ToggleButtonModule,
  ],
  templateUrl: './stream-create.page.html',
  styleUrls: ['./stream-create.page.scss'],
})
export class StreamCreatePage {
  private readonly fb = inject(FormBuilder);
  private readonly streamService = inject(StreamService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly now = new Date();
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | undefined>(undefined);
  protected readonly categories = signal<CategoryResponseDto[]>([]);

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(256)]],
    description: ['', [Validators.maxLength(2048)]],
    categoryId: [null as string | null],
    tags: [[] as string[]],
    maxViewers: [null as number | null],
    autoArchiveChat: [true],
    chatArchiveDelayMinutes: [30],
    scheduledAt: [null as Date | null],
  });

  constructor() {
    this.streamService
      .getCategories()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.categories.set(data),
        error: () =>
          this.errorMessage.set('Failed to load categories. Please refresh.'),
      });
  }

  protected get scheduledAtValue(): Date | null {
    return this.form.get('scheduledAt')?.value ?? null;
  }

  protected get scheduledAtIsFuture(): boolean {
    const val = this.scheduledAtValue;
    if (!val) return false;
    return new Date(val) > new Date();
  }

  protected onSaveDraft(): void {
    if (this.form.get('title')?.invalid || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(undefined);

    const raw = this.form.getRawValue();

    this.streamService
      .create({
        title: raw.title!,
        description: raw.description ?? '',
        category: '',
        categoryId: raw.categoryId ?? undefined,
        tags: raw.tags?.length ? raw.tags : undefined,
        maxViewers: raw.maxViewers ?? 0,
        autoArchiveChat: raw.autoArchiveChat ?? true,
        chatArchiveDelayMinutes: raw.chatArchiveDelayMinutes ?? 30,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => {
          this.loading.set(false);
          // Immediately issue a publish key so the user has a ready-to-copy stream key
          this.streamService.issuePublishKey(response.id).subscribe({
            next: (key) => {
              this.router.navigate(['/channel', response.id], {
                state: { publishKey: key },
              });
            },
            error: () => {
              // Fallback: navigate without key — user can generate manually
              this.router.navigate(['/channel', response.id]);
            },
          });
        },
        error: () => {
          this.loading.set(false);
          this.errorMessage.set('Failed to create stream. Please try again.');
        },
      });
  }

  protected onSchedule(): void {
    const scheduledAt = this.scheduledAtValue;
    if (
      this.form.get('title')?.invalid ||
      this.loading() ||
      !scheduledAt ||
      !this.scheduledAtIsFuture
    ) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(undefined);

    const raw = this.form.getRawValue();

    this.streamService
      .create({
        title: raw.title!,
        description: raw.description ?? '',
        category: '',
        categoryId: raw.categoryId ?? undefined,
        tags: raw.tags?.length ? raw.tags : undefined,
        maxViewers: raw.maxViewers ?? 0,
        autoArchiveChat: raw.autoArchiveChat ?? true,
        chatArchiveDelayMinutes: raw.chatArchiveDelayMinutes ?? 30,
        scheduledAt: scheduledAt.toISOString(),
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.loading.set(false);
          this.router.navigateByUrl('/home');
        },
        error: () => {
          this.loading.set(false);
          this.errorMessage.set('Failed to schedule stream. Please try again.');
        },
      });
  }

  protected onCancel(): void {
    this.router.navigateByUrl('/home');
  }
}
