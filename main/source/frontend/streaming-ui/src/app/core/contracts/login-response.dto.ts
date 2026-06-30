export interface LoginResponseDto {
  readonly status: string;
  readonly message: string;
  readonly accessToken: string;
  readonly expiresInSeconds: number;
  readonly policyVersion: string;
  readonly refreshToken: string;
  readonly refreshExpiresInSeconds: number;
}

/** Shape of the 401 JSON body returned by the gateway when a token is rejected. */
export interface TokenErrorResponse {
  readonly error: string;
  readonly error_code: 'token_expired' | 'invalid_token';
  readonly message: string;
}
