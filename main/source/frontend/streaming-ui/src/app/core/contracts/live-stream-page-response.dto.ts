import { StreamSummaryResponseDto } from './stream-summary-response.dto';

export interface LiveStreamPageResponseDto {
  readonly streams: readonly StreamSummaryResponseDto[];
  readonly nextCursor: string | null;
  readonly hasMore: boolean;
}
