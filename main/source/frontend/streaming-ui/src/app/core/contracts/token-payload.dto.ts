/**
 * Claims extracted from the access-token JWT payload.
 * The gateway validates the signature; the client only reads public claims.
 */
export interface TokenPayloadDto {
  readonly sub: string;
  readonly iss: string;
  readonly aud: string | readonly string[];
  readonly exp: number;
  readonly iat: number;
  readonly jti: string;
  readonly ver: number;
  readonly pv: string;
  readonly ent: readonly string[];
  readonly attr: TokenAttrDto;
}

export interface TokenAttrDto {
  readonly roles: readonly string[];
  readonly tier: string;
  readonly verified_streamer: boolean;
  /** Public channel handle; {@code null} for tokens issued before auth A1. */
  readonly username: string | null;
}
