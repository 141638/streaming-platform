import { HttpClient } from '@angular/common/http';
import {
  computed,
  DestroyRef,
  inject,
  Injectable,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval, Observable, tap } from 'rxjs';
import { BanRequestDto } from '../contracts/ban-request.dto';
import { BanResponseDto } from '../contracts/ban-response.dto';

/** Client-side expiry tick cadence (cosmetic — the server guard is authoritative). */
const EXPIRY_TICK_MS = 30_000;

/**
 * Room-scoped moderation state + API.
 *
 * <p>Provide this at the {@code chat-panel} level (NOT {@code providedIn: 'root'})
 * so the ban list and the expiry tick are torn down per room instance.
 *
 * <p>Temp-ban expiry is lazy on both ends: the server's {@code BanSendGuard}
 * re-checks {@code isActive} on every send, and {@code activeBans} filters lapsed
 * rows on a wall-clock tick — no unban call is needed for expiry.
 */
@Injectable()
export class ChatModerationService {
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly basePath = '/api/chat/v1/rooms';

  private readonly _bans = signal<readonly BanResponseDto[]>([]);
  private readonly _now = signal(Date.now());

  public readonly bans = this._bans.asReadonly();
  public readonly now = this._now.asReadonly();
  public readonly activeBans = computed(() =>
    this._bans().filter(
      (ban) => !ban.expiresAt || Date.parse(ban.expiresAt) > this._now(),
    ),
  );
  public readonly bannedSubjects = computed(
    () => new Set(this.activeBans().map((ban) => ban.bannedSubject)),
  );

  constructor() {
    interval(EXPIRY_TICK_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this._now.set(Date.now()));
  }

  // ── Public API ─────────────────────────────────────────────────────────

  /** Fetch the room's active bans and populate the roster. */
  public loadBans(roomKey: string): Observable<BanResponseDto[]> {
    return this.http
      .get<BanResponseDto[]>(`${this.basePath}/${roomKey}/bans`)
      .pipe(tap((bans) => this._bans.set(bans)));
  }

  /**
   * Ban a subject. On success the returned ban is prepended (replacing any
   * prior ban for the same subject); on error the roster is left untouched.
   * State is mutated via {@code update} against the current value so concurrent
   * loads/mutations are never clobbered by a stale snapshot.
   */
  public ban(
    roomKey: string,
    request: BanRequestDto,
  ): Observable<BanResponseDto> {
    return this.http
      .post<BanResponseDto>(`${this.basePath}/${roomKey}/bans`, request)
      .pipe(
        tap((created) =>
          this._bans.update((current) => [
            created,
            ...current.filter(
              (ban) => ban.bannedSubject !== created.bannedSubject,
            ),
          ]),
        ),
      );
  }

  /**
   * Lift a subject's ban — optimistically removed, restored on error. Both the
   * removal and the rollback re-insert operate on the current value (not a
   * whole-list snapshot), so a concurrent load/mutation is preserved.
   */
  public unban(roomKey: string, subject: string): Observable<void> {
    const removed = this._bans().filter((ban) => ban.bannedSubject === subject);
    this._bans.update((current) =>
      current.filter((ban) => ban.bannedSubject !== subject),
    );
    return this.http
      .delete<void>(
        `${this.basePath}/${roomKey}/bans/${encodeURIComponent(subject)}`,
      )
      .pipe(
        tap({
          error: () =>
            this._bans.update((current) =>
              current.some((ban) => ban.bannedSubject === subject)
                ? current
                : [...current, ...removed],
            ),
        }),
      );
  }
}
