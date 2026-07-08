export interface PublishKeyResponseDto {
  readonly streamId: string;
  readonly srsName: string;
  readonly rtmpUrl: string;
  readonly playUrl: string;
  readonly token: string;
  readonly expiresAt: string;
}
