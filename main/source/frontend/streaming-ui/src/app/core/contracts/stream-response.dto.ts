export interface StreamResponseDto {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly category: string;
  readonly maxViewers: number;
  readonly status: string;
  readonly broadcasterSubject: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly endedAt: string | null;
}
