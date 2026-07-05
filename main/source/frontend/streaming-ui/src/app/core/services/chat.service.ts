import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ChatMessageResponseDto } from '../contracts/chat-message-response.dto';
import { RoomResponseDto } from '../contracts/room-response.dto';
import { SendMessageRequestDto } from '../contracts/send-message-request.dto';

@Injectable({ providedIn: 'root' })
export class ChatService {
  private readonly http = inject(HttpClient);
  private readonly basePath = '/api/chat/v1/rooms';

  public getRoom(roomKey: string): Observable<RoomResponseDto> {
    return this.http.get<RoomResponseDto>(`${this.basePath}/${roomKey}`);
  }

  public sendMessage(
    roomKey: string,
    content: string,
  ): Observable<ChatMessageResponseDto> {
    const body: SendMessageRequestDto = { content };
    return this.http.post<ChatMessageResponseDto>(
      `${this.basePath}/${roomKey}/messages`,
      body,
    );
  }

  public getRecentMessages(
    roomKey: string,
  ): Observable<ChatMessageResponseDto[]> {
    return this.http.get<ChatMessageResponseDto[]>(
      `${this.basePath}/${roomKey}/messages/recent`,
    );
  }

  /**
   * Fetch messages older than the given cursor for lazy-load pagination.
   * @param roomKey the room's external key
   * @param cursor  ISO-8601 timestamp of the oldest message currently loaded
   * @param limit   max number of messages to return (default 30)
   */
  public getMessagesBefore(
    roomKey: string,
    cursor: string,
    limit: number = 30,
  ): Observable<ChatMessageResponseDto[]> {
    return this.http.get<ChatMessageResponseDto[]>(
      `${this.basePath}/${roomKey}/messages/recent`,
      { params: { before: cursor, limit: limit.toString() } },
    );
  }
}
