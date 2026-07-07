export interface StreamSummaryResponseDto {
  readonly id: string;
  readonly title: string;
  readonly status: string;
  readonly category: string;
  readonly categoryId: string | null;
  readonly tags: readonly string[];
  readonly createdAt: string;
  readonly scheduledAt: string | null;
}
