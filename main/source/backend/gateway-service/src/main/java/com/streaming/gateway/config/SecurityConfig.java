package com.streaming.gateway.config;

import com.streaming.pbac.config.Structured401AuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Public endpoints that must bypass JWT validation entirely.
     * <p>
     * A separate filter chain is required because the reactive
     * {@code AuthenticationWebFilter} (configured by
     * {@code .oauth2ResourceServer()}) runs <em>before</em> authorization —
     * even {@code permitAll()} paths would reject an expired JWT.
     * By omitting {@code oauth2ResourceServer} from this chain, requests
     * to these paths pass through regardless of bearer token validity.
     */
    @Bean
    @Order(0)
    public SecurityWebFilterChain publicEndpoints(ServerHttpSecurity http) {
        http
                .securityMatcher(new OrServerWebExchangeMatcher(
                        ServerWebExchangeMatchers.pathMatchers("/actuator/**"),
                        ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,
                                "/api/auth/v1/login",
                                "/api/auth/v1/token/refresh",
                                "/api/auth/v1/logout",
                                "/api/auth/v1/password-reset/request",
                                "/api/auth/v1/password-reset/confirm"
                        ),
                        // SRS webhooks use publish-token auth, not JWT
                        ServerWebExchangeMatchers.pathMatchers(
                                "/api/streams/v1/webhooks/**"
                        )
                ))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // CORS is handled by Spring Cloud Gateway's globalcors config (application.yml).
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().permitAll()
                );
        return http.build();
    }

    /**
     * Protected endpoints — JWT validation with structured 401 responses.
     * <p>
     * This chain catches every request that the public chain does not match.
     */
    @Bean
    @Order(1)
    public SecurityWebFilterChain protectedEndpoints(
            ServerHttpSecurity http,
            ReactiveJwtDecoder decoder
    ) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(decoder))
                        .authenticationEntryPoint(new Structured401AuthenticationEntryPoint())
                );
        return http.build();
    }
}
