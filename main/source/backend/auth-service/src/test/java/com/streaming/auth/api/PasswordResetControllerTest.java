package com.streaming.auth.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streaming.auth.dto.PasswordResetResponse;
import com.streaming.auth.exception.InvalidPasswordResetTokenException;
import com.streaming.auth.exception.PasswordValidationException;
import com.streaming.auth.service.PasswordResetService;
import com.streaming.auth.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordResetController.class)
@Import(SecurityConfig.class)
@DisplayName("PasswordResetController")
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PasswordResetService passwordResetService;

    @Nested
    @DisplayName("POST /v1/password-reset/request")
    class RequestReset {

        @Test
        @DisplayName("returns 200 with ok status for a valid request")
        void validRequest_returns200() throws Exception {
            when(passwordResetService.requestReset(anyString()))
                    .thenReturn(new PasswordResetResponse("ok",
                            "If an account with that email exists, a reset link has been sent."));

            mockMvc.perform(post("/v1/password-reset/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user@example.com\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("returns 400 when email is missing")
        void missingEmail_returns400() throws Exception {
            mockMvc.perform(post("/v1/password-reset/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 400 when email is invalid")
        void invalidEmail_returns400() throws Exception {
            mockMvc.perform(post("/v1/password-reset/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"not-an-email\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /v1/password-reset/confirm")
    class ConfirmReset {

        @Test
        @DisplayName("returns 200 with ok status on successful reset")
        void validConfirm_returns200() throws Exception {
            when(passwordResetService.confirmReset(eq("valid-token"), eq("newPassword123")))
                    .thenReturn(new PasswordResetResponse("ok",
                            "Password has been reset. You may now sign in."));

            mockMvc.perform(post("/v1/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"valid-token\",\"newPassword\":\"newPassword123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("returns 400 when token is invalid or expired")
        void invalidToken_returns400() throws Exception {
            when(passwordResetService.confirmReset(eq("bad-token"), anyString()))
                    .thenThrow(new InvalidPasswordResetTokenException());

            mockMvc.perform(post("/v1/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"bad-token\",\"newPassword\":\"newPassword123\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.message").value("Invalid or expired reset token."));
        }

        @Test
        @DisplayName("returns 422 when password is too short")
        void shortPassword_returns422() throws Exception {
            when(passwordResetService.confirmReset(eq("valid-token"), eq("short")))
                    .thenThrow(new PasswordValidationException("Password must be at least 8 characters."));

            mockMvc.perform(post("/v1/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"valid-token\",\"newPassword\":\"short\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.message").value("Password must be at least 8 characters."));
        }

        @Test
        @DisplayName("returns 400 when token is missing")
        void missingToken_returns400() throws Exception {
            mockMvc.perform(post("/v1/password-reset/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"\",\"newPassword\":\"newPassword123\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
