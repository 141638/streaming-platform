package com.streaming.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Returns a structured JSON 401 body with an {@code error_code} field so
 * clients can distinguish expired tokens (→ refresh + retry) from invalid
 * or missing tokens (→ force logout).
 * <p>
 * Logs the underlying {@code AuthenticationException} message so operators
 * can diagnose bad signatures, malformed JWTs, and clock-skew expiry.
 */
public class CustomServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(CustomServerAuthenticationEntryPoint.class);

    private static final String BODY_EXPIRED = """
            {
              "error": "Unauthorized",
              "error_code": "token_expired",
              "message": "Access token has expired"
            }
            """;

    private static final String BODY_INVALID = """
            {
              "error": "Unauthorized",
              "error_code": "invalid_token",
              "message": "A valid Bearer token is required"
            }
            """;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        String body = isExpired(ex) ? BODY_EXPIRED : BODY_INVALID;

        log.warn("Authentication rejected [{}] for {} {}: {}",
                isExpired(ex) ? "expired" : "invalid",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                ex == null ? null : ex.getMessage()
        );

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Suppress any WWW-Authenticate header so the browser does not
        // pop its native login dialog over the Angular SPA.
        exchange.getResponse().getHeaders().remove("WWW-Authenticate");
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Walk the exception chain for any mention of "expired" (case-insensitive). */
    private static boolean isExpired(AuthenticationException ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase().contains("expired")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
