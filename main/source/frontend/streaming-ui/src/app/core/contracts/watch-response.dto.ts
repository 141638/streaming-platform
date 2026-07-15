import { StreamSummaryResponseDto } from './stream-summary-response.dto';

export interface WatchResponseDto {
  readonly playUrl: string;
  readonly roomKey: string;
  readonly stream: StreamSummaryResponseDto;
  readonly isLive: boolean;
  readonly isChatArchived: boolean;
  readonly thumbnailUrl: string | null;
}
