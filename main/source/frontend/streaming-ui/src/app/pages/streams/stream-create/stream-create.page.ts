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
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { StreamService } from '../../../core/services/stream.service';

@Component({
  selector: 'app-stream-create',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    CardModule,
    ButtonModule,
    InputTextModule,
    InputNumberModule,
    MessageModule,
  ],
  templateUrl: './stream-create.page.html',
  styleUrls: ['./stream-create.page.scss'],
})
export class StreamCreatePage {
  private readonly fb = inject(FormBuilder);
  private readonly streamService = inject(StreamService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | undefined>(undefined);

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(256)]],
    description: ['', [Validators.maxLength(2048)]],
    category: ['', [Validators.maxLength(64)]],
    maxViewers: [null as number | null],
  });

  protected onSubmit(): void {
    if (this.form.invalid || this.loading()) {
      return;
    }

    this.loading.set(true);
    this.errorMessage.set(undefined);

    const raw = this.form.getRawValue();

    this.streamService
      .create({
        title: raw.title!,
        description: raw.description ?? '',
        category: raw.category ?? '',
        maxViewers: raw.maxViewers ?? 0,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => {
          this.loading.set(false);
          this.router.navigateByUrl('/home');
        },
        error: () => {
          this.loading.set(false);
          this.errorMessage.set('Failed to create stream. Please try again.');
        },
      });
  }

  protected onCancel(): void {
    this.router.navigateByUrl('/home');
  }
}
