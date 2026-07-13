import { StreamSummaryResponseDto } from './stream-summary-response.dto';

export interface BroadcastPageMetaDto {
  total: number;
  page: number;
  size: number;
}

export interface BroadcastPageResponseDto {
  data: StreamSummaryResponseDto[];
  meta: BroadcastPageMetaDto;
}
