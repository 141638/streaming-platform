package com.streaming.auth.api;

import com.streaming.auth.dto.LoginRequest;
import com.streaming.auth.dto.LoginResponse;
import com.streaming.auth.dto.RefreshTokenSubmitRequest;
import com.streaming.auth.exception.InvalidRefreshTokenException;
import com.streaming.auth.service.AuthService;
import com.streaming.auth.service.CookieService;
import com.streaming.auth.service.RefreshTokenService;
import com.streaming.auth.token.IssuedSessionTokens;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final CookieService cookieService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        final IssuedSessionTokens issued = this.authService.authenticate(request);
        cookieService.setRefreshTokenCookie(issued.refreshToken(), issued.refreshExpiresInSeconds());
        return ResponseEntity.ok(new LoginResponse(
                "ok",
                "Authenticated as " + request.username(),
                issued.accessToken().accessToken(),
                issued.accessToken().expiresInSeconds(),
                issued.accessToken().policyVersion(),
                issued.refreshToken(),
                issued.refreshExpiresInSeconds()));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenSubmitRequest request) {
        // Cookie-first: browser auto-sends HttpOnly cookie to this path.
        // Body fallback: mobile/native clients that can't use cookies.
        String plaintext = cookieService.getRefreshToken()
                .orElse(request.refreshToken());

        if (plaintext == null || plaintext.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        IssuedSessionTokens issued = refreshTokenService.rotateSession(plaintext);
        cookieService.setRefreshTokenCookie(issued.refreshToken(), issued.refreshExpiresInSeconds());
        return ResponseEntity.ok(new LoginResponse(
                "ok",
                "Refreshed",
                issued.accessToken().accessToken(),
                issued.accessToken().expiresInSeconds(),
                issued.accessToken().policyVersion(),
                issued.refreshToken(),
                issued.refreshExpiresInSeconds()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenSubmitRequest request) {
        String plaintext = cookieService.getRefreshToken()
                .orElse(request.refreshToken());
        if (plaintext != null && !plaintext.isBlank()) {
            refreshTokenService.revokeTokensByRefreshToken(plaintext);
        }
        cookieService.deleteRefreshToken();
        return ResponseEntity.noContent().build();
    }
}
