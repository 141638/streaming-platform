import { HttpClient, HttpBackend, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, Input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { MessageModule } from 'primeng/message';
import { ProgressBarModule } from 'primeng/progressbar';
import { firstValueFrom } from 'rxjs';
import { ChatMessageResponseDto } from '../../../core/contracts/chat-message-response.dto';
import { LoginResponseDto } from '../../../core/contracts/login-response.dto';
import { AuthService } from '../../../core/services/auth.service';

interface LoadTestUser {
  readonly username: string;
  readonly token: string;
}

const TEST_USERS = [
  { username: 'filialKing', password: '123' },
  { username: 'velina.roswell', password: '123' },
  { username: 'papercut.tv', password: '123' },
];

const RANDOM_MESSAGES: readonly string[] = [
  'Hello everyone! 👋',
  'Great stream today!',
  'Can you increase the volume?',
  'First time watching, loving it!',
  '😂😂😂',
  'GG!',
  'How long have you been streaming?',
  'Love the content!',
  'Keep it up! 🔥',
  'What game is this?',
  'Where are you from?',
  'This is awesome!',
  'Subscribed!',
  'Any tips for beginners?',
  'LOL that was hilarious',
  'Can you do a tutorial next time?',
  'Best streamer ever!',
  'PogChamp',
  'Drop your setup specs?',
  'Hello from the other side 🌍',
];

@Component({
  selector: 'app-chat-load-test',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    ButtonModule,
    InputNumberModule,
    MessageModule,
    ProgressBarModule,
  ],
  templateUrl: './chat-load-test.component.html',
})
export class ChatLoadTestComponent {
  /** The room's external key to target. */
  @Input({ required: true }) public roomKey!: string;

  private readonly http = inject(HttpClient);
  private readonly authService = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly directHttp = new HttpClient(inject(HttpBackend));

  protected readonly messageCount = this.fb.control(50, [Validators.required, Validators.min(1), Validators.max(5000)]);
  protected readonly running = signal(false);
  protected readonly sent = signal(0);
  protected readonly failed = signal(0);
  protected readonly errorMessage = signal<string | null>(null);

  protected get progressPercent(): number {
    const total = this.messageCount.value ?? 1;
    return Math.round(((this.sent() + this.failed()) / total) * 100);
  }

  /**
   * Log in all test users + current user, then fire random messages
   * in batches to the target room.
   */
  protected async runLoadTest(): Promise<void> {
    const count = this.messageCount.value;
    if (!count || count <= 0 || this.running()) {
      return;
    }

    this.running.set(true);
    this.sent.set(0);
    this.failed.set(0);
    this.errorMessage.set(null);

    // 1 — Gather tokens for all 4 identities
    const identities: LoadTestUser[] = [];

    // current user (already logged in — grab token from AuthService)
    const currentToken = this.authService.accessToken();
    if (currentToken) {
      const sub = this.parseSub(currentToken) ?? 'me';
      identities.push({ username: sub, token: currentToken });
    }

    // test users
    for (const user of TEST_USERS) {
      try {
        const res = await firstValueFrom(
          this.http.post<LoginResponseDto>('/api/auth/v1/login', {
            username: user.username,
            password: user.password,
          }),
        );
        identities.push({ username: user.username, token: res.accessToken });
      } catch (err: unknown) {
        const msg =
          err instanceof HttpErrorResponse
            ? `Login failed for ${user.username}: ${err.status} ${err.statusText}`
            : `Login failed for ${user.username}`;
        this.errorMessage.set(msg);
        this.running.set(false);
        return;
      }
    }

    // 2 — Fire messages
    for (let i = 0; i < count; i++) {
      const user = identities[Math.floor(Math.random() * identities.length)];
      const body = this.randomMessage();

      try {
        await firstValueFrom(
          this.directHttp.post<ChatMessageResponseDto>(
            `/api/chat/v1/rooms/${this.roomKey}/messages`,
            body,
            {
              headers: { Authorization: `Bearer ${user.token}` },
            },
          ),
        );
        this.sent.update((n) => n + 1);
      } catch {
        this.failed.update((n) => n + 1);
      }

      // yield to the browser occasionally so the UI doesn't freeze
      if (i % 10 === 9) {
        await this.delay(0);
      }
    }

    this.running.set(false);
  }

  private randomMessage(): { content: string } {
    return {
      content: RANDOM_MESSAGES[Math.floor(Math.random() * RANDOM_MESSAGES.length)],
    };
  }

  private parseSub(token: string): string | null {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }
      const payload = JSON.parse(atob(parts[1]));
      return payload?.sub ?? null;
    } catch {
      return null;
    }
  }

  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }
}
