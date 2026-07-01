import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateStreamRequestDto } from '../contracts/create-stream-request.dto';
import { StreamResponseDto } from '../contracts/stream-response.dto';
import { StreamSummaryResponseDto } from '../contracts/stream-summary-response.dto';

@Injectable({ providedIn: 'root' })
export class StreamService {
  private readonly http = inject(HttpClient);

  public create(
    request: CreateStreamRequestDto,
  ): Observable<StreamResponseDto> {
    return this.http.post<StreamResponseDto>(
      '/api/streams/v1/streams',
      request,
    );
  }

  public listMyStreams(): Observable<StreamSummaryResponseDto[]> {
    return this.http.get<StreamSummaryResponseDto[]>(
      '/api/streams/v1/streams',
    );
  }
}
