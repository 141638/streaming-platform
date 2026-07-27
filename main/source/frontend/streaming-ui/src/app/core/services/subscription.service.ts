import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { PreferenceRequestDto } from '../contracts/preference-request.dto';
import { PreferenceResponseDto } from '../contracts/preference-response.dto';
import { SubscriptionRequestDto } from '../contracts/subscription-request.dto';
import { SubscriptionResponseDto } from '../contracts/subscription-response.dto';
import { IdempotencyService } from './idempotency.service';

/**
 * HTTP client for notification subscription and delivery-preference APIs.
 *
 * <p>All endpoints are authenticated via the HTTP interceptor — the JWT
 * {@code sub} claim is extracted server-side for ownership enforcement.
 * No client-side auth wiring is needed beyond what the interceptor provides.
 */
@Injectable({ providedIn: 'root' })
export class SubscriptionService {
  private readonly http = inject(HttpClient);

  private readonly basePath = '/api/notifications/v1';

  // ── Subscriptions (follow / unfollow) ──────────────────────────────────

  /** Follow a target. Idempotent — returns existing subscription on 409. */
  public follow(
    targetType: string,
    targetId: string,
    idempotencyKey?: string,
  ): Observable<SubscriptionResponseDto> {
    const body: SubscriptionRequestDto = { targetType, targetId };
    return this.http.put<SubscriptionResponseDto>(
      `${this.basePath}/subscriptions`,
      body,
      IdempotencyService.options(idempotencyKey),
    );
  }

  /** Unfollow a target — soft delete. */
  public unfollow(
    id: string,
    idempotencyKey?: string,
  ): Observable<void> {
    return this.http.delete<void>(
      `${this.basePath}/subscriptions/${encodeURIComponent(id)}`,
      IdempotencyService.options(idempotencyKey),
    );
  }

  /** Get the current user's subscriptions, newest first. */
  public getMySubscriptions(targetType?: string): Observable<SubscriptionResponseDto[]> {
    let params = new HttpParams();
    if (targetType) {
      params = params.set('target_type', targetType);
    }
    return this.http.get<SubscriptionResponseDto[]>(
      `${this.basePath}/subscriptions`,
      { params },
    );
  }

  /** Check if the current user is following a specific target — read-only. */
  public checkSubscription(
    targetType: string,
    targetId: string,
  ): Observable<SubscriptionResponseDto> {
    const params = new HttpParams()
      .set('target_type', targetType)
      .set('target_id', targetId);
    return this.http.get<SubscriptionResponseDto>(
      `${this.basePath}/subscriptions/check`,
      { params },
    );
  }

  // ── Preferences (delivery channel toggles) ─────────────────────────────

  /** Create or update a delivery preference for a channel. Idempotent. */
  public upsertPreference(
    channel: string,
    topicGlob: string | null,
    idempotencyKey?: string,
  ): Observable<PreferenceResponseDto> {
    const body: PreferenceRequestDto = { channel, topicGlob };
    return this.http.put<PreferenceResponseDto>(
      `${this.basePath}/preferences`,
      body,
      IdempotencyService.options(idempotencyKey),
    );
  }

  /** Get all delivery preferences for the current user. */
  public getMyPreferences(): Observable<PreferenceResponseDto[]> {
    return this.http.get<PreferenceResponseDto[]>(
      `${this.basePath}/preferences`,
    );
  }

  /** Update a specific preference — partial update, ownership-scoped. */
  public updatePreference(
    id: string,
    body: { active?: boolean; topicGlob?: string | null },
    idempotencyKey?: string,
  ): Observable<PreferenceResponseDto> {
    return this.http.patch<PreferenceResponseDto>(
      `${this.basePath}/preferences/${encodeURIComponent(id)}`,
      body,
      IdempotencyService.options(idempotencyKey),
    );
  }

  /** Delete a preference — ownership-scoped. */
  public deletePreference(
    id: string,
    idempotencyKey?: string,
  ): Observable<void> {
    return this.http.delete<void>(
      `${this.basePath}/preferences/${encodeURIComponent(id)}`,
      IdempotencyService.options(idempotencyKey),
    );
  }
}
