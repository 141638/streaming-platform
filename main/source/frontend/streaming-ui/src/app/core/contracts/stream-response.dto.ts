export interface StreamResponseDto {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly category: string;
  readonly categoryId: string | null;
  readonly tags: readonly string[];
  readonly maxViewers: number;
  readonly status: string;
  readonly broadcasterSubject: string;
  readonly thumbnailUrl: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly startedAt: string | null;
  readonly scheduledAt: string | null;
  readonly endedAt: string | null;
}
