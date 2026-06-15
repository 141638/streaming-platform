package com.streaming.auth.api;

import com.streaming.auth.dto.LoginRequest;
import com.streaming.auth.dto.LoginResponse;
import com.streaming.auth.dto.RefreshTokenSubmitRequest;
import com.streaming.auth.service.AuthService;
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

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        final IssuedSessionTokens issued = this.authService.authenticate(request);
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
        IssuedSessionTokens issued = refreshTokenService.rotateSession(request.refreshToken());
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
}
