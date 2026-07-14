import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BroadcastPageResponseDto } from '../contracts/broadcast-page-response.dto';
import { CategoryResponseDto } from '../contracts/category-response.dto';
import { ChannelAboutResponseDto } from '../contracts/channel-about-response.dto';
import { ChannelHomeResponseDto } from '../contracts/channel-home-response.dto';
import { ChannelIdentityResponseDto } from '../contracts/channel-identity-response.dto';
import { CreateStreamRequestDto } from '../contracts/create-stream-request.dto';
import { PublishKeyResponseDto } from '../contracts/publish-key-response.dto';
import { SocialLinkDto } from '../contracts/social-link.dto';
import { StreamResponseDto } from '../contracts/stream-response.dto';
import { LiveStreamPageResponseDto } from '../contracts/live-stream-page-response.dto';
import { StreamSummaryResponseDto } from '../contracts/stream-summary-response.dto';
import { WatchResponseDto } from '../contracts/watch-response.dto';

@Injectable({ providedIn: 'root' })
export class StreamService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/streams/v1';

  // ── Discovery ──────────────────────────────────────────────────────────

  /**
   * Returns cursor-paginated live streams with optional keyword search.
   * Use {@code cursor} from a previous page to fetch the next page.
   */
  public getLiveStreams(params?: {
    keyword?: string;
    cursor?: string;
    limit?: number;
  }): Observable<LiveStreamPageResponseDto> {
    const q = new URLSearchParams();
    if (params?.keyword) q.set('keyword', params.keyword);
    if (params?.cursor) q.set('cursor', params.cursor);
    if (params?.limit) q.set('limit', String(params.limit));
    const qs = q.toString();
    return this.http.get<LiveStreamPageResponseDto>(
      `${this.base}/streams/live${qs ? '?' + qs : ''}`,
    );
  }

  // ── Watch ──────────────────────────────────────────────────────────────

  /** Returns playback URL, room key, and stream metadata for the watch page. */
  public getWatchData(id: string): Observable<WatchResponseDto> {
    return this.http.get<WatchResponseDto>(
      `${this.base}/streams/${id}/watch`,
    );
  }

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

  /** DRAFT → returns publish key for OBS. The actual LIVE transition
   *  happens via the SRS on_publish webhook. */
  public startStream(id: string): Observable<PublishKeyResponseDto> {
    return this.http.post<PublishKeyResponseDto>(
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
  public goLive(id: string): Observable<PublishKeyResponseDto> {
    return this.http.post<PublishKeyResponseDto>(
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

  /** Fetch channel identity for the page header — only the newest 1 session. */
  public getChannelIdentity(
    username: string,
  ): Observable<ChannelIdentityResponseDto> {
    return this.http.get<ChannelIdentityResponseDto>(
      `${this.base}/channels/${username}/identity`,
    );
  }

  /** Fetch home tab data: 15-session rail + recent categories. */
  public getChannelHome(
    username: string,
  ): Observable<ChannelHomeResponseDto> {
    return this.http.get<ChannelHomeResponseDto>(
      `${this.base}/channels/${username}/home`,
    );
  }

  /** Fetch about tab data: bio, social links, and channel stats. */
  public getChannelAbout(
    username: string,
  ): Observable<ChannelAboutResponseDto> {
    return this.http.get<ChannelAboutResponseDto>(
      `${this.base}/channels/${username}/about`,
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

  // ── Viewer presence ─────────────────────────────────────────────────────

  /** Send a heartbeat to register this viewer as present. */
  public sendHeartbeat(id: string): Observable<void> {
    return this.http.post<void>(
      `${this.base}/streams/${id}/heartbeat`,
      null,
    );
  }

  /** Get the current viewer count for a stream. */
  public getViewerCount(id: string): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(
      `${this.base}/streams/${id}/viewers`,
    );
  }

  // ── Archive ──────────────────────────────────────────────────────────────

  /** Archive an ended stream. Owner-only. */
  public archiveStream(id: string): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/archive`,
      null,
    );
  }

  // ── Broadcasts (archived streams, channel-facing read) ───────────────────

  /** Top 10 most recent archived streams for a channel. */
  public getRecentBroadcasts(
    username: string,
  ): Observable<StreamSummaryResponseDto[]> {
    return this.http.get<StreamSummaryResponseDto[]>(
      `${this.base}/channels/${username}/broadcasts/recent`,
    );
  }

  /**
   * Paginated, filterable list of archived streams for a channel.
   * Only returns streams where {@code archived_url IS NOT NULL}.
   */
  public getBroadcasts(
    username: string,
    params: {
      keyword?: string;
      sort?: string;
      order?: string;
      page?: number;
      size?: number;
    },
  ): Observable<BroadcastPageResponseDto> {
    const q = new URLSearchParams();
    if (params.keyword) q.set('keyword', params.keyword);
    if (params.sort) q.set('sort', params.sort);
    if (params.order) q.set('order', params.order);
    q.set('page', String(params.page ?? 0));
    q.set('size', String(params.size ?? 24));
    return this.http.get<BroadcastPageResponseDto>(
      `${this.base}/channels/${username}/broadcasts?${q.toString()}`,
    );
  }
}
