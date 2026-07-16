/** Backend wire type for {@code PUT /v1/preferences} request body. */
export interface PreferenceRequestDto {
  readonly channel: string;
  readonly topicGlob: string | null;
}
