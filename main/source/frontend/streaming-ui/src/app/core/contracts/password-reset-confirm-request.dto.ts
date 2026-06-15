export interface PasswordResetConfirmRequestDto {
  readonly token: string;
  readonly newPassword: string;
}
