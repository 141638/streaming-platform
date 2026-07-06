package com.streaming.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.auth.dto.PasswordResetResponse;
import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.exception.PasswordValidationException;
import com.streaming.auth.persistence.entity.UserAccountEntity;
import com.streaming.auth.infrastructure.redis.RefreshTokenRedisService;
import com.streaming.auth.persistence.repository.UserAccountRepository;
import com.streaming.auth.service.MailCommonService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetService")
class PasswordResetServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RefreshTokenRedisService redisService;

    @Mock
    private PasswordResetTokenService passwordResetTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private MailCommonService mailCommonService;

    private PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetService(
                userAccountRepository,
                redisService,
                passwordResetTokenService,
                passwordEncoder,
                mailCommonService);
    }

    @Nested
    @DisplayName("requestReset")
    class RequestReset {

        @Test
        @DisplayName("returns ok even when email is not found")
        void unknownEmail_returnsOk() {
            when(userAccountRepository.findByEmailAndDeleteFlagFalse("unknown@example.com"))
                    .thenReturn(Optional.empty());

            PasswordResetResponse response = passwordResetService.requestReset("unknown@example.com");

            assertThat(response.status()).isEqualTo("ok");
            assertThat(response.message()).contains("If an account with that email exists");
            verify(passwordResetTokenService, never()).issueForUser(any());
        }

        @Test
        @DisplayName("returns ok and issues token when email exists")
        void knownEmail_issuesToken() {
            UUID userId = UUID.randomUUID();
            UserAccountEntity user = new UserAccountEntity();
            user.setId(userId);
            user.setEmail("user@example.com");

            when(userAccountRepository.findByEmailAndDeleteFlagFalse("user@example.com"))
                    .thenReturn(Optional.of(user));
            when(passwordResetTokenService.issueForUser(userId)).thenReturn("mock-jwt");

            PasswordResetResponse response = passwordResetService.requestReset("user@example.com");

            assertThat(response.status()).isEqualTo("ok");
            assertThat(response.message()).contains("If an account with that email exists");
            verify(passwordResetTokenService).issueForUser(userId);
        }

        @Test
        @DisplayName("trims and lowercases the email before lookup")
        void normalizesEmail() {
            when(userAccountRepository.findByEmailAndDeleteFlagFalse("user@example.com"))
                    .thenReturn(Optional.empty());

            passwordResetService.requestReset(" User@Example.com ");

            verify(userAccountRepository).findByEmailAndDeleteFlagFalse("user@example.com");
        }
    }

    @Nested
    @DisplayName("confirmReset")
    class ConfirmReset {

        @Test
        @DisplayName("updates password and revokes refresh tokens on success")
        void validToken_updatesPasswordAndRevokes() {
            UUID userId = UUID.randomUUID();
            UserAccountEntity user = new UserAccountEntity();
            user.setId(userId);
            user.setEmail("user@example.com");
            user.setPasswordHash("old-hash");

            when(passwordResetTokenService.validateAndDecode("valid-token")).thenReturn(userId);
            when(userAccountRepository.findByIdAndDeleteFlagFalse(userId)).thenReturn(Optional.of(user));
            when(passwordEncoder.encode("newPassword123")).thenReturn("new-hash");
            // revokeAllByUser is void — mockito no-ops by default for void methods

            PasswordResetResponse response =
                    passwordResetService.confirmReset("valid-token", "newPassword123");

            assertThat(response.status()).isEqualTo("ok");
            assertThat(response.message()).contains("Password has been reset");
            assertThat(user.getPasswordHash()).isEqualTo("new-hash");
            assertThat(user.getUpdatedAt()).isNotNull();
            verify(redisService).revokeAllByUser(userId);
            verify(userAccountRepository).save(user);
        }

        @Test
        @DisplayName("throws InvalidPasswordResetTokenException when token validation fails")
        void invalidToken_throws() {
            when(passwordResetTokenService.validateAndDecode("bad-token"))
                    .thenThrow(new InvalidPasswordResetTokenException());

            assertThatThrownBy(() -> passwordResetService.confirmReset("bad-token", "newPassword123"))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
            verify(userAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws InvalidPasswordResetTokenException when user does not exist or is deleted")
        void userNotFound_throws() {
            UUID userId = UUID.randomUUID();
            when(passwordResetTokenService.validateAndDecode("valid-token")).thenReturn(userId);
            when(userAccountRepository.findByIdAndDeleteFlagFalse(userId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> passwordResetService.confirmReset("valid-token", "newPassword123"))
                    .isInstanceOf(InvalidPasswordResetTokenException.class);
        }

        @Test
        @DisplayName("throws PasswordValidationException when password is too short")
        void shortPassword_throws() {
            UUID userId = UUID.randomUUID();
            UserAccountEntity user = new UserAccountEntity();
            user.setId(userId);

            when(passwordResetTokenService.validateAndDecode("valid-token")).thenReturn(userId);
            when(userAccountRepository.findByIdAndDeleteFlagFalse(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> passwordResetService.confirmReset("valid-token", "short"))
                    .isInstanceOf(PasswordValidationException.class)
                    .hasMessageContaining("at least 8 characters");
        }

        @Test
        @DisplayName("throws PasswordValidationException when password is null")
        void nullPassword_throws() {
            UUID userId = UUID.randomUUID();
            UserAccountEntity user = new UserAccountEntity();
            user.setId(userId);

            when(passwordResetTokenService.validateAndDecode("valid-token")).thenReturn(userId);
            when(userAccountRepository.findByIdAndDeleteFlagFalse(userId)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> passwordResetService.confirmReset("valid-token", null))
                    .isInstanceOf(PasswordValidationException.class);
        }
    }
}
