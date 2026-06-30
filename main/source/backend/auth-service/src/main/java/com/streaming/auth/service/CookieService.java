package com.streaming.auth.service;

import com.streaming.auth.token.JwtIssuerProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@Service
@RequestScope
@RequiredArgsConstructor
public class CookieService {

    private static final String REFRESH_TOKEN_NAME = "refresh_token";

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final JwtIssuerProperties jwtProperties;

    // ── Refresh token convenience methods ────────────────────────────────

    /** Read the refresh token from the {@code refresh_token} cookie. */
    public Optional<String> getRefreshToken() {
        return getCookie(REFRESH_TOKEN_NAME)
                .filter(v -> !v.isBlank());
    }

    /** Set the {@code refresh_token} cookie using configured defaults. */
    public void setRefreshTokenCookie(String value, long ttlSeconds) {
        setCookie(REFRESH_TOKEN_NAME, value, CookieOptions.builder()
                .expireSeconds(ttlSeconds)
                .path(jwtProperties.cookiePath())
                .httpOnly(jwtProperties.cookieHttpOnly())
                .sameSite(jwtProperties.cookieSameSite())
                .secure(jwtProperties.cookieSecure())
                .build());
    }

    /** Delete the {@code refresh_token} cookie. */
    public void deleteRefreshToken() {
        setCookie(REFRESH_TOKEN_NAME, "", CookieOptions.builder()
                .expireSeconds(0L)
                .path(jwtProperties.cookiePath())
                .httpOnly(jwtProperties.cookieHttpOnly())
                .sameSite(jwtProperties.cookieSameSite())
                .secure(jwtProperties.cookieSecure())
                .build());
    }

    // ── Generic cookie operations ─────────────────────────────────────────

    public Optional<String> getCookie(String name) {
        return Optional.ofNullable(request.getCookies())
                .flatMap(cookies -> Arrays.stream(cookies)
                        .filter(c -> c.getName().equals(name))
                        .map(Cookie::getValue)
                        .findFirst());
    }

    public void setCookie(String name, String value, CookieOptions options) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(options.getHttpOnly() != null ? options.getHttpOnly() : true)
                .secure(options.getSecure() != null ? options.getSecure() : false)
                .sameSite(coalesce(options.getSameSite(), "Lax"));

        if (options.getPath() != null && !options.getPath().isBlank()) {
            builder.path(options.getPath());
        } else {
            builder.path("/");
        }

        long seconds = options.getExpireSeconds() != null
                ? options.getExpireSeconds()
                : options.getExpireDays() != null
                        ? options.getExpireDays() * 24L * 60L * 60L
                        : 24L * 60L * 60L;
        builder.maxAge(Duration.ofSeconds(seconds));

        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    @Builder
    @Data
    public static class CookieOptions {
        private Long expireSeconds;
        private Integer expireDays;
        private String path;
        private Boolean httpOnly;
        private Boolean secure;
        private String sameSite;
    }

    private static String coalesce(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
