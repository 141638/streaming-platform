package com.streaming.auth.service;

import com.streaming.auth.dto.PasswordResetResponse;
import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.exception.PasswordValidationException;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.infrastructure.redis.RefreshTokenRedisService;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

/**
 * Handles password-reset flow: request (always returns 200 to prevent user enumeration) and confirm
 * (validates reset JWT, updates password, revokes all active refresh tokens).
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserAccountRepository userAccountRepository;
    private final RefreshTokenRedisService redisService;
    private final PasswordResetTokenService passwordResetTokenService;
    private final PasswordEncoder passwordEncoder;
    private final MailCommonService mailCommonService;

    /**
     * Processes a password-reset request. Always returns the same 200 response regardless of whether
     * the email exists to prevent user enumeration.
     */
    public PasswordResetResponse requestReset(String email) {
        String normalized = email.trim().toLowerCase();

        var userOpt = userAccountRepository.findByEmailAndDeleteFlagFalse(normalized);

        if (userOpt.isEmpty()) {
            log.info("Password reset requested for unknown email");
            return new PasswordResetResponse("ok",
                    "If an account with that email exists, a reset link has been sent.");
        }

        UserAccountEntity user = userOpt.get();
        String token = passwordResetTokenService.issueForUser(user.getId());
        String callbackUrl = "http://localhost:4200/password-reset?token=" + token;

        String subject = "Reset your Streaming Platform password";
        String htmlBody = buildResetEmail(user.getUsername(), callbackUrl);

        try {
            mailCommonService.send(user.getEmail(), subject, Collections.emptyList(), htmlBody);
        } catch (MessagingException e) {
            log.error("Failed to send password-reset email to user {}", user.getId(), e);
        }

        return new PasswordResetResponse("ok", "If an account with that email exists, a reset link has been sent.");
    }

    /**
     * Validates the reset token, updates the user's password, and revokes all active refresh tokens
     * so the user is signed out everywhere on password change.
     */
    @Transactional
    public PasswordResetResponse confirmReset(String token, String newPassword) {
        UUID userId = passwordResetTokenService.validateAndDecode(token);

        UserAccountEntity user = userAccountRepository
                .findByIdAndDeleteFlagFalse(userId)
                .orElseThrow(InvalidPasswordResetTokenException::new);

        validateNewPassword(newPassword);

        String hashed = passwordEncoder.encode(newPassword);
        user.setPasswordHash(hashed);
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        redisService.revokeAllByUser(userId);
        log.info("Password reset for user {}: revoked active refresh tokens", userId);

        userAccountRepository.save(user);

        return new PasswordResetResponse("ok", "Password has been reset. You may now sign in.");
    }

    private void validateNewPassword(String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new PasswordValidationException("Password must be at least 8 characters.");
        }
    }

    /**
     * Builds a plain HTML password-reset email that renders reliably across
     * desktop and mobile clients with inline styles and a preheader for
     * preview-snippet control.
     */
    private String buildResetEmail(String username, String callbackUrl) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="color-scheme" content="dark light">
                <title>Reset your password</title>
                </head>
                <body style="margin:0;padding:0;background-color:#0a0a0f;font-family:system-ui,-apple-system,sans-serif">
                <!--[if mso]><div style="display:none;font-size:1px;color:#0a0a0f;line-height:1px;max-height:0;overflow:hidden"> \
                Someone requested a password reset for your Streaming Platform account. \
                </div><![endif]-->
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#0a0a0f">
                  <tr>
                    <td align="center" style="padding:40px 16px">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:480px">
                        <!-- Header -->
                        <tr>
                          <td style="padding-bottom:24px;text-align:center">
                            <span style="font-size:24px;color:#2563eb">&#9655;</span>
                            <span style="font-size:16px;font-weight:600;color:#e5e5e5;vertical-align:middle;padding-left:6px">
                              Streaming Platform
                            </span>
                          </td>
                        </tr>
                        <!-- Card -->
                        <tr>
                          <td style="background-color:rgba(23,23,35,0.7);border:1px solid #2a2a3a;border-radius:12px;padding:32px 28px">
                            <h1 style="margin:0 0 8px;font-size:22px;font-weight:700;color:#f5f5f5;letter-spacing:-0.01em">
                              Reset your password
                            </h1>
                            <p style="margin:0 0 20px;font-size:14px;line-height:1.6;color:#9ca3af">
                              Hi %s,<br><br>
                              Someone requested a password reset for your account.
                              Click the button below to choose a new password.
                              This link expires in <strong style="color:#e5e5e5">5 minutes</strong>.
                            </p>
                            <!-- Button -->
                            <table role="presentation" cellpadding="0" cellspacing="0" style="margin-bottom:24px">
                              <tr>
                                <td align="center" style="background-color:#2563eb;border-radius:8px">
                                  <a href="%s"
                                     style="display:inline-block;padding:12px 32px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;white-space:nowrap">
                                    Reset password
                                  </a>
                                </td>
                              </tr>
                            </table>
                            <!-- Fallback link -->
                            <p style="margin:0 0 16px;font-size:12px;line-height:1.5;color:#6b7280">
                              If the button doesn&rsquo;t work, copy and paste this link:
                            </p>
                            <p style="margin:0;font-size:12px;line-height:1.6;color:#4b5563;word-break:break-all">
                              %s
                            </p>
                          </td>
                        </tr>
                        <!-- Footer -->
                        <tr>
                          <td style="padding-top:20px;text-align:center">
                            <p style="margin:0;font-size:11px;color:#4b5563">
                              If you didn&rsquo;t request this, you can safely ignore this email.
                              <br>&copy; Streaming Platform
                            </p>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(username, callbackUrl, callbackUrl);
    }
}
