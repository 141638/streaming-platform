import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CategoryResponseDto } from '../contracts/category-response.dto';
import { ChannelResponseDto } from '../contracts/channel-response.dto';
import { CreateStreamRequestDto } from '../contracts/create-stream-request.dto';
import { PublishKeyResponseDto } from '../contracts/publish-key-response.dto';
import { SocialLinkDto } from '../contracts/social-link.dto';
import { StreamResponseDto } from '../contracts/stream-response.dto';
import { StreamSummaryResponseDto } from '../contracts/stream-summary-response.dto';

@Injectable({ providedIn: 'root' })
export class StreamService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/streams/v1';

  // ── Categories ──────────────────────────────────────────────────────────

  public getCategories(): Observable<CategoryResponseDto[]> {
    return this.http.get<CategoryResponseDto[]>(`${this.base}/categories`);
  }

  // ── Streams ─────────────────────────────────────────────────────────────

  public create(
    request: CreateStreamRequestDto,
  ): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(`${this.base}/streams`, request);
  }

  public listMyStreams(): Observable<StreamSummaryResponseDto[]> {
    return this.http.get<StreamSummaryResponseDto[]>(`${this.base}/streams`);
  }

  public getStream(id: string): Observable<StreamResponseDto> {
    return this.http.get<StreamResponseDto>(`${this.base}/streams/${id}`);
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  public startStream(id: string): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/start`,
      null,
    );
  }

  public endStream(id: string): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/end`,
      null,
    );
  }

  public cancelStream(id: string): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/cancel`,
      null,
    );
  }

  /** SCHEDULED → DRAFT with a fresh publish key (see stream ADR-0001/0004). */
  public goLive(id: string): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/go-live`,
      null,
    );
  }

  // ── Publish key ─────────────────────────────────────────────────────────

  /** View the existing publish key; the raw token is masked as {@code ****}. */
  public getPublishKey(id: string): Observable<PublishKeyResponseDto> {
    return this.http.get<PublishKeyResponseDto>(
      `${this.base}/streams/${id}/publish-key`,
    );
  }

  /** Issue or rotate the publish key; returns the raw token once. */
  public issuePublishKey(id: string): Observable<PublishKeyResponseDto> {
    return this.http.post<PublishKeyResponseDto>(
      `${this.base}/streams/${id}/publish-key`,
      null,
    );
  }

  // ── Channel page (authenticated, cross-user read) ────────────────────────

  /** Fetch the safe channel projection for any user by username. */
  public getChannel(username: string): Observable<ChannelResponseDto> {
    return this.http.get<ChannelResponseDto>(
      `${this.base}/channels/${username}`,
    );
  }

  // ── Channel profile (owner-only write) ─────────────────────────────────────

  /** Update the channel profile (bio + social links). Only the channel owner may call this. */
  public updateProfile(
    username: string,
    bio: string,
    socialLinks: readonly SocialLinkDto[] | null,
  ): Observable<void> {
    return this.http.post<void>(`${this.base}/channels/${username}/profile`, {
      bio,
      socialLinks,
    });
  }
}
