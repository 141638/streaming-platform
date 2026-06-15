export interface LoginResponseDto {
  readonly status: string;
  readonly message: string;
  readonly accessToken: string;
  readonly expiresInSeconds: number;
  readonly policyVersion: string;
  readonly refreshToken: string;
  readonly refreshExpiresInSeconds: number;
}
