/** Backend wire type returned by preference endpoints. */
export interface PreferenceResponseDto {
  readonly id: string;
  readonly subscriberSubject: string;
  readonly channel: string;
  readonly topicGlob: string | null;
  readonly active: boolean;
  readonly createdAt: string;
  readonly updatedAt: string;
}
