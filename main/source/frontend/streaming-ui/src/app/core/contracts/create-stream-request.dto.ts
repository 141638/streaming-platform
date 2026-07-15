export interface CreateStreamRequestDto {
  readonly title: string;
  readonly description: string;
  readonly category: string;
  readonly categoryId?: string;
  readonly tags?: readonly string[];
  readonly maxViewers: number;
  readonly autoArchiveChat?: boolean;
  readonly chatArchiveDelayMinutes?: number;
  readonly scheduledAt?: string;
}
