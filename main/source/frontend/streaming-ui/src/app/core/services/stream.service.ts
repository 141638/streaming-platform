import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CategoryResponseDto } from '../contracts/category-response.dto';
import { CreateStreamRequestDto } from '../contracts/create-stream-request.dto';
import { ScheduleStreamRequestDto } from '../contracts/schedule-stream-request.dto';
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
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams`,
      request,
    );
  }

  public listMyStreams(): Observable<StreamSummaryResponseDto[]> {
    return this.http.get<StreamSummaryResponseDto[]>(
      `${this.base}/streams`,
    );
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

  public scheduleStream(
    id: string,
    request: ScheduleStreamRequestDto,
  ): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      `${this.base}/streams/${id}/schedule`,
      request,
    );
  }
}
