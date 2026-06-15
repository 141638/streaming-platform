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
 * Returns a structured JSON 401 body instead of an empty response,
 * giving API consumers a readable error for missing or invalid tokens.
 * <p>
 * Logs the underlying {@code AuthenticationException} message so operators
 * can distinguish expired tokens, bad signatures, and malformed JWTs.
 */
public class CustomServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(CustomServerAuthenticationEntryPoint.class);

    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"Unauthorized\",\"message\":\"A valid Bearer token is required\"}";

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        log.warn("Authentication rejected for {} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),
                ex.getMessage());
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
